package cz.kvalitacena.network

import kotlinx.serialization.json.JsonElement

/**
 * Chyba z GraphQL API — mobilní protějšek `frontend/src/app/services/graphql-service.ts`
 * `GraphQlAppError` (docs/lokalizace.md, kontrakt chyby). `message` (z [Exception]) je
 * `serverMessage`, aby dosavadní `catch (e: Exception) { ... e.message ?: fallback }` na
 * ViewModelech dál fungovalo beze změny, jen teď dostává lokalizovaný text ze serveru podle
 * `Accept-Language` (viz `AcceptLanguageInterceptor`) místo dřívějšího syrového spojení
 * `errors[].message`. `code`/`params` čeká `Throwable.toUiText()` (`ui/common/ErrorMessages.kt`)
 * pro vlastní překlad, jakmile appka daný kód zná.
 */
class GraphQlAppException(
  val code: String?,
  val params: List<JsonElement>,
  val serverMessage: String,
) : Exception(serverMessage)

/** Chyba mimo GraphQL kontrakt — REST volání (MediaClient upload) nebo transportní selhání. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Backend odmítl request kvůli staré verzi appky (HTTP 426, [ClientVersionInterceptor]) —
 * vydáním APK zamrzne GraphQL kontrakt (docs/vydani.md), takže starší klient v terénu by na
 * pozdější breaking změnu schématu jinak spadl na nesrozumitelnou parse chybu. ViewModely tuhle
 * výjimku nemusí zvlášť ošetřovat — [ClientVersionInterceptor] zároveň nastaví
 * `UpdateRequiredState.required`, které `MainActivity` zobrazí jako blokující obrazovku.
 */
class ClientUpdateRequiredException : Exception("Client version too old")
