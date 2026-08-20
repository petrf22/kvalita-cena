package cz.kvalitacena.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Posílá `X-Client-Kind: ANDROID` u KAŽDÉHO requestu (GraphQL/REST/Coil sdílí jeden
 * `OkHttpClient`, viz `AppContainer`) — dřív ji ručně nastavoval jen `AuthRepository` pro pár
 * REST endpointů (`/api/auth/...`), takže GraphQL volání z mobilu (`submitObservations`,
 * `submitFeedback`) na serveru vypadala jako web (`ObservationGraphQlController.resolveSource`,
 * `FeedbackGraphQlController.resolveClientKind` — obě čtou stejnou hlavičku, bez ní default
 * na `WEB`). `AuthRepository` hlavičku nechává nastavenou ručně dál (netříští se tím nic,
 * hodnota je stejná), jen se tu doplňuje chybějící pokrytí pro GraphQL.
 */
class ClientKindInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request().newBuilder()
      .header("X-Client-Kind", "ANDROID")
      .build()
    return chain.proceed(request)
  }
}
