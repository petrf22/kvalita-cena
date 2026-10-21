package cz.kvalitacena.network

import android.content.Context
import android.net.Uri
import cz.kvalitacena.auth.AuthRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Upload fotky (core.media) — REST multipart na backend MediaController, ne GraphQL (ten
 * multipart v tomhle projektu nepodporuje, graphql-multipart-request-spec, viz backend
 * schema.graphqls). Popisek/pořadí a smazání jdou přes [GraphQlClient.updatePhoto]/
 * [GraphQlClient.deletePhoto] — stejné rozdělení jako na webu (frontend media-service.ts).
 */
class MediaClient(private val authRepository: AuthRepository, private val client: OkHttpClient) {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Účtenka je až `app.receipt.max-images-per-receipt` (30) výřezů v jednom requestu a server
   * každý překresluje — sdílených 10 s z `AppContainer` by na pomalém signálu z obchodu
   * nestačilo. Odvozený klient sdílí connection pool i interceptory, liší se jen timeouty.
   */
  private val receiptClient: OkHttpClient by lazy {
    client.newBuilder()
      .writeTimeout(60, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
  }

  /** RFC 7807 `ProblemDetail` tvar — jen pole, která appka umí zobrazit (viz backend `GlobalExceptionHandler`). */
  @Serializable
  private data class ProblemDetailBody(val detail: String? = null, val code: String? = null)

  /**
   * [uri] je typicky výsledek `TakePicture`/`PickVisualMedia` — čte se přes ContentResolver,
   * appka si sama neřídí dočasné soubory na disku (kromě `FileProvider` cesty pro kameru).
   */
  suspend fun upload(
    context: Context,
    recordType: String,
    recordId: String,
    uri: Uri,
    caption: String? = null,
    kind: String? = null,
    // Jazyk obalu/etikety na fotce — u LABEL je to podstata věci (etiketa je vyfocený text
    // složení). Bez něj backend dosadí jazyk requestu (docs/lokalizace.md).
    lang: String? = null,
  ): Photo =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val mimeType = resolver.getType(uri) ?: "image/jpeg"
      val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw TransportException("Fotku se nepodařilo přečíst")
      val extension = if (mimeType.contains("png")) "png" else "jpg"

      val multipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "upload.$extension", bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
        .apply { if (!caption.isNullOrBlank()) addFormDataPart("caption", caption) }
        .apply { if (!kind.isNullOrBlank()) addFormDataPart("kind", kind) }
        .apply { if (!lang.isNullOrBlank()) addFormDataPart("lang", lang) }
        .build()

      uploadAttempt(
        "${ApiConfig.BASE_URL}/api/media/$recordType/$recordId",
        multipartBody,
        "Nahrání fotky selhalo",
        allowRecovery = true,
        Photo.serializer(),
      )
    }

  /**
   * Avatar profilu — na rozdíl od [upload] vždy NAHRADÍ předchozí avatar (recordId se na
   * backendu bere z Authentication, ne z URL, viz MediaController.uploadAvatar).
   */
  suspend fun uploadAvatar(context: Context, uri: Uri): Photo =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val mimeType = resolver.getType(uri) ?: "image/jpeg"
      val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw TransportException("Fotku se nepodařilo přečíst")
      val extension = if (mimeType.contains("png")) "png" else "jpg"

      val multipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "avatar.$extension", bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
        .build()

      uploadAttempt(
        "${ApiConfig.BASE_URL}/api/media/user/avatar",
        multipartBody,
        "Nahrání avataru selhalo",
        allowRecovery = true,
        Photo.serializer(),
      )
    }

  /**
   * Nahrání účtenky (docs/soukromi.md, „Účtenky") — víc výřezů jedním requestem, v [images]
   * v pořadí shora dolů. Díly jdou ze souborů v `filesDir/receipts/pending/` (ReceiptUploadWorker),
   * `asRequestBody` je jde při opakovaném pokusu po obnově tokenu přečíst znovu. [capturedAt]
   * je ISO-8601 s offsetem — kdy uživatel fotil, ne kdy se účtenka dostala na server
   * (observed_at ≠ created_at, offline zápis). Vrací id nové účtenky.
   *
   * Chyba nese HTTP status ([HttpAppException.status]), ať worker odliší „zkusit znovu"
   * (výpadek, 5xx, 429) od „nemá smysl opakovat" (chybí souhlas, denní limit).
   */
  suspend fun uploadReceipt(
    images: List<File>,
    consentVersion: Int,
    archiveConsent: Boolean,
    capturedAt: String,
    clientUploadId: String,
  ): Long = withContext(Dispatchers.IO) {
    val jpeg = "image/jpeg".toMediaType()
    val multipartBody = MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .apply { images.forEachIndexed { index, file -> addFormDataPart("images", "part-$index.jpg", file.asRequestBody(jpeg)) } }
      .addFormDataPart("consentVersion", consentVersion.toString())
      .addFormDataPart("archiveConsent", archiveConsent.toString())
      .addFormDataPart("capturedAt", capturedAt)
      // Stejný při každém opakování z fronty — server pak vrátí už uloženou účtenku.
      .addFormDataPart("clientUploadId", clientUploadId)
      .build()
    uploadAttempt(
      "${ApiConfig.BASE_URL}/api/receipts",
      multipartBody,
      "Nahrání účtenky selhalo",
      allowRecovery = true,
      ReceiptUploadResponse.serializer(),
      receiptClient,
    ).id
  }

  /**
   * Obrázek účtenky jen pro vlastníka (`Receipt.imagePaths`) — do paměti, ne do diskové cache
   * Coilu: server ho posílá s `Cache-Control: private, no-store` a obsah nákupu na disku
   * telefonu navíc nemá co dělat.
   */
  suspend fun receiptImage(path: String): ByteArray = withContext(Dispatchers.IO) {
    receiptImageAttempt(path, allowRecovery = true)
  }

  private suspend fun receiptImageAttempt(path: String, allowRecovery: Boolean): ByteArray {
    val token = authRepository.validAccessToken()
    val builder = Request.Builder().url(ApiConfig.BASE_URL + path).get()
    token?.let { builder.header("Authorization", "Bearer $it") }
    client.newCall(builder.build()).execute().use { response ->
      if (!response.isSuccessful) {
        if (token != null && allowRecovery && response.code == 401 &&
          authRepository.recoverFromUnauthorized(token)
        ) {
          return receiptImageAttempt(path, allowRecovery = false)
        }
        throw TransportException("Obrázek účtenky se nepodařilo načíst (${response.code})")
      }
      return response.body?.bytes() ?: throw TransportException("Prázdný obrázek účtenky")
    }
  }

  /**
   * [allowRecovery] = false na opakovaném pokusu po [AuthRepository.recoverFromUnauthorized] —
   * jinak by nekonečně zkoušel refresh dokola, kdyby 401 přišlo i s čerstvým tokenem (např.
   * účet mezitím zanikl). [multipartBody] jde bezpečně poslat znovu — části jsou postavené
   * z `ByteArray` v paměti (`toRequestBody`), ne z jednorázového streamu.
   */
  private suspend fun <T> uploadAttempt(
    url: String,
    multipartBody: MultipartBody,
    fallbackAction: String,
    allowRecovery: Boolean,
    deserializer: KSerializer<T>,
    httpClient: OkHttpClient = client,
  ): T {
    val token = authRepository.validAccessToken()
    val builder = Request.Builder().url(url).post(multipartBody)
    token?.let { builder.header("Authorization", "Bearer $it") }

    httpClient.newCall(builder.build()).execute().use { response ->
      val bodyString = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        // Vypršelý/neplatný access token se serveru tváří jako "nikdy nepřihlášen" a vrátí
        // stejné PHOTO_*_REQUIRES_LOGIN (401) jako skutečný anonym — proto zkoušíme tichý
        // refresh na libovolné 401, ne jen na konkrétní kód.
        if (token != null && allowRecovery && response.code == 401
          && authRepository.recoverFromUnauthorized(token)
        ) {
          return uploadAttempt(url, multipartBody, fallbackAction, allowRecovery = false, deserializer, httpClient)
        }
        throw errorFor(response, bodyString, fallbackAction)
      }
      return json.decodeFromString(deserializer, bodyString)
    }
  }

  /**
   * Rozbalí `ProblemDetail` tělo (backend `GlobalExceptionHandler`) do [HttpAppException] se
   * skutečnou lokalizovanou hláškou — appka dřív tělo zahazovala a ukázala jen obecné
   * "Něco se pokazilo" bez ohledu na to, co se skutečně stalo (a backend obchodní výjimky
   * (`AppException`) nikdy neloguje, takže bez tohohle nešlo příčinu dohledat ani v konzoli).
   * [fallbackAction] + HTTP kód je nouzová záloha pro odpověď, která tenhle tvar nemá vůbec
   * (např. request spadl dřív, než stihl doběhnout do `GlobalExceptionHandler`).
   */
  private fun errorFor(response: Response, bodyString: String, fallbackAction: String): Exception {
    val problem = runCatching { json.decodeFromString(ProblemDetailBody.serializer(), bodyString) }.getOrNull()
    val detail = problem?.detail
    return if (!detail.isNullOrBlank()) {
      HttpAppException(problem.code, detail, response.code)
    } else {
      TransportException("$fallbackAction (${response.code})", status = response.code)
    }
  }
}
