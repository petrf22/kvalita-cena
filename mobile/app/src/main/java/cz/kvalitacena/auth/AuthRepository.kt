package cz.kvalitacena.auth

import android.content.Context
import android.os.SystemClock
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.network.AccountDeleteConfirmBody
import cz.kvalitacena.network.ApiConfig
import cz.kvalitacena.network.EmailChangeConfirmBody
import cz.kvalitacena.network.EmailChangeRequestBody
import cz.kvalitacena.network.HttpAppException
import cz.kvalitacena.network.OtpRequestBody
import cz.kvalitacena.network.OtpRequestResponse
import cz.kvalitacena.network.OtpVerifyBody
import cz.kvalitacena.network.RefreshBody
import cz.kvalitacena.network.TokenResponse
import cz.kvalitacena.network.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Passwordless auth (e-mail → OTP kód → token) — mobilní protějšek k
 * frontend/src/app/services/auth-service.ts. Na rozdíl od webu refresh token nejde jako
 * cookie (Android nemá prohlížečovou cookie jar), ale v těle odpovědi → {@link TokenStore}
 * (EncryptedSharedPreferences). Access token žije jen v paměti procesu (docs/soukromi.md).
 */
class AuthRepository(context: Context, private val client: OkHttpClient) {

  private val tokenStore = TokenStore(context.applicationContext)
  private val json = Json { ignoreUnknownKeys = true }
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private val _accessToken = MutableStateFlow<String?>(null)

  /** Monotonní čas (`SystemClock.elapsedRealtime`), dokdy platí token v [_accessToken]. */
  private var accessTokenExpiresAt: Long? = null

  /**
   * Refresh token na serveru ROTUJE a jeho znovupoužití mimo 30s grace okno revokuje celou
   * rodinu tokenů (`RefreshTokenService.rotate`) — pro appku by to znamenalo tiché odhlášení
   * "kvůli podezření na krádež". Souběžné dotazy proto nesmí spustit dvě rotace naráz; obnova
   * běží vždy jen jednou (single-flight) a ostatní počkají na její výsledek.
   */
  private val refreshMutex = Mutex()

  /**
   * Je uživatel přihlášený — POZOR, ne "máme v paměti access token". Ten po startu procesu
   * chybí, dokud nedoběhne první obnova, a odvozovat z něj stav přihlášení znamenalo ukázat
   * přihlašovací formulář někomu, kdo přihlášený je. Zdroj pravdy je přítomnost refresh tokenu
   * v [TokenStore], protože jen jeho ztráta/odmítnutí je skutečné odhlášení.
   */
  private val _isLoggedIn = MutableStateFlow(false)
  val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

  /**
   * Ví už appka, jestli je uživatel přihlášený? Čtení [TokenStore] jde přes Android Keystore
   * (desítky ms) a na hlavní vlákno při startu nepatří, takže [isLoggedIn] je do té doby
   * `false` jen proto, že se ještě nezjistilo — ne proto, že by uživatel byl anonym.
   * Obrazovka, která podle toho PŘEPÍNÁ obsah (Účet mezi přihlášením a účtem), musí počkat,
   * jinak přihlášenému na okamžik problikne přihlašovací formulář.
   */
  private val _sessionKnown = MutableStateFlow(false)
  val sessionKnown: StateFlow<Boolean> = _sessionKnown

  /**
   * Jediný způsob, jak se v appce dostat k access tokenu — nikdy nečíst uložený token přímo.
   * Vrátí token, který je JEŠTĚ platný; jinak ho tiše obnoví z refresh tokenu.
   *
   * Čekat s obnovou na chybu ze serveru nejde: prošlý access token `JwtAuthenticationFilter`
   * mlčky zahodí a request doběhne jako ANONYMNÍ (HTTP 200, žádné `errors`), takže dotaz
   * s anonymní variantou — `me`, `searchProducts` s vlastními DRAFTy, okno grafu — vrátí
   * normální, jen ochuzenou odpověď. Appka pak tvrdila "Přihlášen" a přitom serveru byla cizí,
   * dokud na to náhodou nenarazil dotaz, který přihlášení vyžaduje (`Moje příspěvky`).
   *
   * `null` znamená skutečného anonyma (žádný refresh token) — ten se nezdržuje ani jedním
   * requestem navíc, anonymní chod appky (T0) je plnohodnotný.
   */
  suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
    usableAccessToken()?.let { return@withContext it }
    if (tokenStore.getRefreshToken() == null) {
      markSession(loggedIn = false)
      return@withContext null
    }
    refreshMutex.withLock {
      // Mezitím mohl token obnovit jiný souběžný dotaz — pak není co rotovat.
      usableAccessToken() ?: run {
        // Čerstvě vydaný token se použije, i kdyby mu do rezervy zbývalo míň — rezerva
        // rozhoduje, KDY obnovit, ne co se smí použít. Server smí mít TTL kratší než rezerva
        // (v testovacím prostředí běžné) a appka by se s `usableAccessToken()` na tomhle řádku
        // zacyklila do trvalé anonymity: každý token by rovnou zahodila jako "skoro prošlý".
        //
        // Když ale obnova NEPROJDE, prošlý token se poslat nesmí: server ho tiše zahodí a
        // request odbaví jako anonymní (HTTP 200) — přesně ta tichá degradace, kvůli které
        // tahle metoda vznikla. Další request obnovu stejně zkusí znovu (hned na začátku),
        // takže se tím o nic nepřipravíme.
        if (refreshLocked()) _accessToken.value else null
      }
    }
  }

  /**
   * Obnova při startu appky. Stav přihlášení nastaví hned z uloženého refresh tokenu (ať
   * obrazovky nečekají na síť), token obnoví jen tehdy, když v paměti žádný platný není —
   * jinak by každé znovuvytvoření Activity (třeba po přepnutí jazyka) zbytečně rotovalo
   * refresh token proti 30s grace oknu.
   */
  suspend fun restoreSession() = withContext(Dispatchers.IO) {
    markSession(loggedIn = tokenStore.getRefreshToken() != null)
    validAccessToken()
    Unit
  }

  private fun usableAccessToken(): String? {
    val token = _accessToken.value ?: return null
    return token.takeIf { isAccessTokenUsable(accessTokenExpiresAt, SystemClock.elapsedRealtime()) }
  }

  suspend fun requestOtp(email: String): OtpRequestResponse = withContext(Dispatchers.IO) {
    val body = json.encodeToString(OtpRequestBody(email)).toRequestBody(jsonMediaType)
    val request = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/auth/otp/request")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
      .build()

    client.newCall(request).execute().use { response ->
      // errorFor rozbalí ProblemDetail tělo (rate limit, pozastavený účet, …) do skutečné
      // lokalizované hlášky — bez něj appka ukazovala jen "Odeslání kódu selhalo (429)" místo
      // toho, co server ve skutečnosti řekl (stejná mezera, kterou errorFor už řeší jinde
      // v tomhle souboru, jen requestOtp/verifyOtp na ni zapomněly).
      if (!response.isSuccessful) throw errorFor(response, "Odeslání kódu selhalo")
      json.decodeFromString<OtpRequestResponse>(response.body!!.string())
    }
  }

  suspend fun verifyOtp(challengeUid: String, code: String, email: String, termsAccepted: Boolean): TokenResponse =
    withContext(Dispatchers.IO) {
      val body = json.encodeToString(OtpVerifyBody(challengeUid, code, email, termsAccepted)).toRequestBody(jsonMediaType)
      val request = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/otp/verify")
        .header("X-Client-Kind", "ANDROID")
        .post(body)
        .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw errorFor(response, "Ověření kódu selhalo")
        val token = json.decodeFromString<TokenResponse>(response.body!!.string())
        applyToken(token)
        token
      }
    }

  /**
   * Vlastní síťová obnova. Volat VÝHRADNĚ se zamčeným [refreshMutex] — kvůli rotaci refresh
   * tokenu (viz tam) nesmí běžet dvakrát naráz.
   *
   * Rozlišuje odmítnutí od nedostupnosti, protože důsledek je opačný: HTTP 401 znamená, že
   * refresh token je neplatný, vypršelý nebo revokovaný (`SESSION_EXPIRED`) — session je
   * fakticky pryč a musí zmizet i z appky, ať `isLoggedIn` nelže. Cokoli jiného (výpadek sítě,
   * chyba serveru) session NECHÁVÁ být: odhlásit člověka za to, že projel tunelem, by ho
   * připravilo o přihlášení kvůli ničemu.
   */
  private suspend fun refreshLocked(): Boolean {
    val refreshToken = tokenStore.getRefreshToken() ?: run {
      clearSession()
      return false
    }
    val body = json.encodeToString(RefreshBody(refreshToken)).toRequestBody(jsonMediaType)
    val request = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/auth/refresh")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
      .build()

    return try {
      client.newCall(request).execute().use { response ->
        when {
          response.code == 401 -> {
            AppLog.e("Obnova přihlášení odmítnuta (401) — session se ruší.")
            clearSession()
            false
          }
          !response.isSuccessful -> {
            AppLog.e("Obnova přihlášení selhala (${response.code}) — session zůstává.")
            false
          }
          else -> {
            applyToken(json.decodeFromString<TokenResponse>(response.body!!.string()))
            true
          }
        }
      }
    } catch (e: Exception) {
      AppLog.e("Obnova přihlášení se nezdařila: ${e::class.simpleName} — session zůstává.", e)
      false
    }
  }

  /**
   * Server odmítl token, který appka považovala za platný — vypršelý být neměl
   * ([validAccessToken] ho hlídá), takže jde o důvod na straně serveru: inkrement
   * `token_version` (globální odhlášení), pozastavený účet, restart backendu s jiným
   * `JWT_SECRET`. Zkusí se jedna obnova; když neprojde kvůli 401, [refreshLocked] session
   * rovnou zruší.
   *
   * [usedToken] je token, se kterým volající narazil — když mezitím jiný souběžný dotaz
   * obnovu už udělal, nová rotace se nekoná a volající jen dostane čerstvý token.
   */
  suspend fun recoverFromUnauthorized(usedToken: String?): Boolean = withContext(Dispatchers.IO) {
    refreshMutex.withLock {
      val current = usableAccessToken()
      if (current != null && current != usedToken) return@withLock true
      refreshLocked()
    }
  }

  /**
   * Odhlášení musí zrušit session POD [refreshMutex] — jinak by obnova, která zrovna běží,
   * doběhla až po vyčištění a [applyToken] by uživatele mlčky přihlásila zpátky (a nechala mu
   * na disku použitelný refresh token). Dřív šla obnova jen při startu a po 401, takže okno
   * bylo úzké; teď se obnovuje na expiraci, tedy kdykoli.
   */
  suspend fun logout() = withContext(Dispatchers.IO) {
    val refreshToken = refreshMutex.withLock {
      val token = tokenStore.getRefreshToken()
      clearSession()
      token
    }
    if (refreshToken != null) {
      val body = json.encodeToString(RefreshBody(refreshToken)).toRequestBody(jsonMediaType)
      val request = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/logout")
        .header("X-Client-Kind", "ANDROID")
        .post(body)
        .build()
      try {
        client.newCall(request).execute().close()
      } catch (e: Exception) {
        // Odhlášení lokálně proběhlo i tak — server-side revokace není kritická pro UX.
      }
    }
  }

  /**
   * Změna přihlašovacího e-mailu — VLASTNÍ tok vedle přihlašovacího OTP (docs/soukromi.md,
   * "Profil uživatele a viditelnost"): kód jde vždy na NOVOU adresu, jinak by šlo o pole ve
   * formuláři profilu, kterým by se dal účet překlepem zamknout. Odpověď je stejná bez ohledu
   * na to, jestli je adresa volná, nebo už patří jinému účtu (enumerace účtů zůstává nemožná).
   */
  suspend fun requestEmailChange(newEmail: String): OtpRequestResponse = withContext(Dispatchers.IO) {
    val body = json.encodeToString(EmailChangeRequestBody(newEmail)).toRequestBody(jsonMediaType)
    val builder = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/auth/email/change/request")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
    validAccessToken()?.let { builder.header("Authorization", "Bearer $it") }

    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful) throw TransportException("Odeslání kódu selhalo (${response.code})")
      json.decodeFromString<OtpRequestResponse>(response.body!!.string())
    }
  }

  /**
   * Potvrzení nové adresy — na serveru inkrementuje token_version (odhlásí ostatní zařízení,
   * stejný mechanismus jako "podezření na krádež", docs/soukromi.md), proto si hned poté appka
   * obnoví vlastní access token, ať aktuální relace nemusí čekat na chybu a teprve pak refresh.
   */
  suspend fun confirmEmailChange(challengeUid: String, code: String, newEmail: String): Boolean =
    withContext(Dispatchers.IO) {
      val body = json.encodeToString(EmailChangeConfirmBody(challengeUid, code, newEmail))
        .toRequestBody(jsonMediaType)
      val builder = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/email/change/confirm")
        .header("X-Client-Kind", "ANDROID")
        .post(body)
      validAccessToken()?.let { builder.header("Authorization", "Bearer $it") }

      client.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) throw TransportException("Změna e-mailu selhala (${response.code})")
      }
      recoverFromUnauthorized(_accessToken.value)
    }

  /** Výmaz účtu (docs/soukromi.md, "GDPR") — dvoukrokový OTP tok jako změna e-mailu, jen na
   *  už vlastněnou adresu ({@code AccountController.java}). Nikdy nenabízí volbu, co se stane
   *  s cenovými zápisy — appka je vždy jen anonymizuje, nikdy skutečně nemaže. */
  suspend fun requestAccountDelete(): OtpRequestResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/me/delete/request")
      .header("X-Client-Kind", "ANDROID")
      .post("".toRequestBody(jsonMediaType))
    validAccessToken()?.let { builder.header("Authorization", "Bearer $it") }

    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful) throw errorFor(response, "Odeslání kódu selhalo")
      json.decodeFromString<OtpRequestResponse>(response.body!!.string())
    }
  }

  /** Účet po úspěchu na serveru zaniká, appka proto rovnou zahodí lokální token stejně jako {@link logout}. */
  suspend fun confirmAccountDelete(challengeUid: String, code: String): Unit = withContext(Dispatchers.IO) {
    val body = json.encodeToString(AccountDeleteConfirmBody(challengeUid, code)).toRequestBody(jsonMediaType)
    val builder = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/me/delete/confirm")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
    validAccessToken()?.let { builder.header("Authorization", "Bearer $it") }

    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful) throw errorFor(response, "Smazání účtu selhalo")
    }
    // Pod zámkem ze stejného důvodu jako logout() — souběžná obnova by session vzkřísila.
    refreshMutex.withLock { clearSession() }
  }

  /** RFC 7807 `ProblemDetail` tvar — jen pole, která appka umí zobrazit (viz backend `GlobalExceptionHandler`). */
  @Serializable
  private data class ProblemDetailBody(val detail: String? = null, val code: String? = null)

  /**
   * Rozbalí `ProblemDetail` tělo do [HttpAppException] se skutečnou lokalizovanou hláškou —
   * stejný princip jako `MediaClient.errorFor`, jen tenhle soubor dřív REST chyby (na rozdíl
   * od GraphQL) zahazoval úplně a ukazoval jen obecný text s HTTP kódem (viz web
   * `shared/error-message.ts`, stejná mezera do nedávna).
   */
  private fun errorFor(response: Response, fallbackAction: String): Exception {
    val bodyString = response.body?.string().orEmpty()
    val problem = runCatching { json.decodeFromString(ProblemDetailBody.serializer(), bodyString) }.getOrNull()
    val detail = problem?.detail
    return if (!detail.isNullOrBlank()) {
      HttpAppException(problem.code, detail)
    } else {
      TransportException("$fallbackAction (${response.code})")
    }
  }

  private fun applyToken(token: TokenResponse) {
    _accessToken.value = token.accessToken
    accessTokenExpiresAt = accessTokenExpiresAt(SystemClock.elapsedRealtime(), token.expiresInSec)
    token.refreshToken?.let { tokenStore.saveRefreshToken(it) }
    markSession(loggedIn = true)
  }

  /** Konec session — v paměti i na disku, ať `isLoggedIn` napříč appkou nelže. */
  private fun clearSession() {
    _accessToken.value = null
    accessTokenExpiresAt = null
    tokenStore.clear()
    markSession(loggedIn = false)
  }

  private fun markSession(loggedIn: Boolean) {
    _isLoggedIn.value = loggedIn
    _sessionKnown.value = true
  }
}
