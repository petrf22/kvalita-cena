package cz.kvalitacena.auth

import android.content.Context
import cz.kvalitacena.network.ApiConfig
import cz.kvalitacena.network.OtpRequestBody
import cz.kvalitacena.network.OtpRequestResponse
import cz.kvalitacena.network.OtpVerifyBody
import cz.kvalitacena.network.RefreshBody
import cz.kvalitacena.network.TokenResponse
import cz.kvalitacena.network.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
  val accessToken: StateFlow<String?> = _accessToken

  suspend fun requestOtp(email: String): OtpRequestResponse = withContext(Dispatchers.IO) {
    val body = json.encodeToString(OtpRequestBody(email)).toRequestBody(jsonMediaType)
    val request = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/auth/otp/request")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw TransportException("Odeslání kódu selhalo (${response.code})")
      json.decodeFromString<OtpRequestResponse>(response.body!!.string())
    }
  }

  suspend fun verifyOtp(challengeUid: String, code: String, email: String): TokenResponse =
    withContext(Dispatchers.IO) {
      val body = json.encodeToString(OtpVerifyBody(challengeUid, code, email)).toRequestBody(jsonMediaType)
      val request = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/otp/verify")
        .header("X-Client-Kind", "ANDROID")
        .post(body)
        .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw TransportException("Ověření kódu selhalo (${response.code})")
        val token = json.decodeFromString<TokenResponse>(response.body!!.string())
        applyToken(token)
        token
      }
    }

  /** Zkusí obnovit přihlášení z uloženého refresh tokenu (volá se při startu appky). */
  suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
    val refreshToken = tokenStore.getRefreshToken() ?: return@withContext false
    val body = json.encodeToString(RefreshBody(refreshToken)).toRequestBody(jsonMediaType)
    val request = Request.Builder()
      .url("${ApiConfig.BASE_URL}/api/auth/refresh")
      .header("X-Client-Kind", "ANDROID")
      .post(body)
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use false
        applyToken(json.decodeFromString<TokenResponse>(response.body!!.string()))
        true
      }
    } catch (e: Exception) {
      false
    }
  }

  suspend fun logout() = withContext(Dispatchers.IO) {
    val refreshToken = tokenStore.getRefreshToken()
    _accessToken.value = null
    tokenStore.clear()
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

  private fun applyToken(token: TokenResponse) {
    _accessToken.value = token.accessToken
    token.refreshToken?.let { tokenStore.saveRefreshToken(it) }
  }
}
