package cz.kvalitacena.network

import android.content.Context
import android.net.Uri
import cz.kvalitacena.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Upload fotky (core.media) — REST multipart na backend MediaController, ne GraphQL (ten
 * multipart v tomhle projektu nepodporuje, graphql-multipart-request-spec, viz backend
 * schema.graphqls). Popisek/pořadí a smazání jdou přes [GraphQlClient.updatePhoto]/
 * [GraphQlClient.deletePhoto] — stejné rozdělení jako na webu (frontend media-service.ts).
 */
class MediaClient(private val authRepository: AuthRepository, private val client: OkHttpClient) {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * [uri] je typicky výsledek `TakePicture`/`PickVisualMedia` — čte se přes ContentResolver,
   * appka si sama neřídí dočasné soubory na disku (kromě `FileProvider` cesty pro kameru).
   */
  suspend fun upload(context: Context, recordType: String, recordId: String, uri: Uri, caption: String? = null): Photo =
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
        .build()

      val builder = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/media/$recordType/$recordId")
        .post(multipartBody)
      authRepository.accessToken.value?.let { builder.header("Authorization", "Bearer $it") }

      client.newCall(builder.build()).execute().use { response ->
        val bodyString = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw TransportException("Nahrání fotky selhalo (${response.code})")
        json.decodeFromString(Photo.serializer(), bodyString)
      }
    }
}
