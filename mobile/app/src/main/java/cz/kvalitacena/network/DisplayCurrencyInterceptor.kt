package cz.kvalitacena.network

import cz.kvalitacena.ui.settings.DisplayCurrencyStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * X-Display-Currency jen když si uživatel zvolil jinou měnu než "měnu obchodu" (docs/lokalizace.md,
 * "Kurzovní lístek a zobrazovací měna") — stejný vzorec jako [AcceptLanguageInterceptor], jen
 * bez fallbacku (chybějící hlavička = appka nic nepřepočítává, žádná změna oproti dnešku).
 */
class DisplayCurrencyInterceptor(private val store: DisplayCurrencyStore) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val currency = store.currency ?: return chain.proceed(chain.request())
    val request = chain.request().newBuilder()
      .header("X-Display-Currency", currency.code)
      .build()
    return chain.proceed(request)
  }
}
