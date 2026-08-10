package cz.kvalitacena.network

import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Posílá aktuální jazyk appky (docs/lokalizace.md) — server podle něj lokalizuje `message`
 * v GraphQL chybách a jazyk OTP e-mailu (Accept-Language, stejně jako frontend
 * `func/language-interceptor.ts`). `AppCompatDelegate.setApplicationLocales`
 * (`ui/settings/LocaleController.kt`) udržuje `Locale.getDefault()` v souladu s volbou
 * uživatele, appka tak nemusí nikam tahat `Context` jen kvůli aktuálnímu jazyku.
 */
class AcceptLanguageInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request().newBuilder()
      .header("Accept-Language", Locale.getDefault().language)
      .build()
    return chain.proceed(request)
  }
}
