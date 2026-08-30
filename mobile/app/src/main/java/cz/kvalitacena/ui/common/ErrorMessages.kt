package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.HttpAppException
import cz.kvalitacena.network.TransportException

/**
 * Mapuje výjimku ze síťové vrstvy na [UiText] (docs/lokalizace.md) — mobilní protějšek webového
 * `shared/error-message.ts`. Appka zatím netypuje `ErrorCode` z GraphQL schématu jako web
 * (`enum-labels.ts`/`ERROR_CODE_KEYS` — codegen tu chybí, viz CLAUDE.md), takže vlastní klientský
 * překlad podle `code` chybí; `serverMessage` (GraphQL i REST — `HttpAppException`,
 * `MediaClient.kt`) je ale VŽDY lokalizovaná podle `Accept-Language` (`AcceptLanguageInterceptor`),
 * takže i neznámý kód appka zobrazí správným jazykem, jen bez appce vlastního doladění textu.
 *
 * [fallback] je pro chyby MIMO síťový kontrakt (appka se vůbec nedovolala serveru, nebo
 * odpověď nešla rozparsovat) — tam appka žádnou lokalizovanou zprávu k dispozici nemá, takže
 * volající obrazovka může dát vlastní obecnější text (např. `search_failed`) místo úplně
 * generického [R.string.error_generic].
 */
fun Throwable.toUiText(@StringRes fallback: Int = R.string.error_generic): UiText = when (this) {
  is GraphQlAppException -> UiText.Raw(serverMessage)
  is HttpAppException -> UiText.Raw(serverMessage)
  // TransportException.message je vždy neprázdný (konstruktor ho vyžaduje) — dřív appka tenhle
  // popisek (např. "Fotku se nepodařilo přečíst") zahazovala úplně stejně jako HttpAppException.
  is TransportException -> message?.let { UiText.Raw(it) } ?: UiText.Res(fallback)
  else -> UiText.Res(fallback)
}
