package cz.kvalitacena

import android.content.Context
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.network.AcceptLanguageInterceptor
import cz.kvalitacena.network.ClientVersionInterceptor
import cz.kvalitacena.network.DisplayCurrencyInterceptor
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.MediaClient
import cz.kvalitacena.ui.settings.DisplayCurrencyStore
import okhttp3.OkHttpClient

/** Ruční DI bez Hiltu/Daggeru — appka je malá, jeden container stačí. */
object AppContainer {
  lateinit var httpClient: OkHttpClient
    private set

  lateinit var authRepository: AuthRepository
    private set

  lateinit var graphQlClient: GraphQlClient
    private set

  lateinit var mediaClient: MediaClient
    private set

  lateinit var displayCurrencyStore: DisplayCurrencyStore
    private set

  fun init(context: Context) {
    if (::authRepository.isInitialized) return
    displayCurrencyStore = DisplayCurrencyStore(context.applicationContext)
    // Jeden sdílený klient pro GraphQL/REST/Coil místo tří samostatných OkHttpClient()
    // instancí — sdílí connection pool a všude jde stejný Accept-Language i X-Display-Currency
    // (docs/lokalizace.md).
    httpClient = OkHttpClient.Builder()
      .addInterceptor(AcceptLanguageInterceptor())
      .addInterceptor(DisplayCurrencyInterceptor(displayCurrencyStore))
      .addInterceptor(ClientVersionInterceptor())
      .build()
    authRepository = AuthRepository(context.applicationContext, httpClient)
    graphQlClient = GraphQlClient(authRepository, httpClient)
    mediaClient = MediaClient(authRepository, httpClient)
  }
}
