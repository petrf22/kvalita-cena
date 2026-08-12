package cz.kvalitacena.network

import cz.kvalitacena.BuildConfig
import cz.kvalitacena.ui.common.UpdateRequiredState
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Posílá `versionCode` appky v hlavičce `X-Client-Version` (docs/vydani.md) — backend ho
 * porovná s `app.client.min-android-version` (`ClientVersionFilter`) a pod prahem vrátí 426.
 * Vydáním APK zamrzne GraphQL kontrakt, takže starý klient v terénu potřebuje zablokovat
 * CELOU appku srozumitelnou obrazovkou (`UpdateRequiredState` → `MainActivity`), ne nechat
 * každé volání spadnout na vlastní nesrozumitelnou chybu.
 */
class ClientVersionInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request().newBuilder()
      .header("X-Client-Version", BuildConfig.VERSION_CODE.toString())
      .build()
    val response = chain.proceed(request)

    if (response.code == 426) {
      UpdateRequiredState.required = true
      response.close()
      throw ClientUpdateRequiredException()
    }

    return response
  }
}
