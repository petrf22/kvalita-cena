package cz.kvalitacena.ui.common

import cz.kvalitacena.R
import cz.kvalitacena.network.GraphQlAppException
import cz.kvalitacena.network.HttpAppException

/**
 * Mapuje výjimku ze síťové vrstvy na [UiText] (docs/lokalizace.md) — mobilní protějšek webového
 * `shared/error-message.ts`. Appka zatím netypuje `ErrorCode` z GraphQL schématu jako web
 * (`enum-labels.ts`/`ERROR_CODE_KEYS` — codegen tu chybí, viz CLAUDE.md), takže vlastní klientský
 * překlad podle `code` chybí; `serverMessage` (GraphQL i REST — `HttpAppException`,
 * `MediaClient.kt`) je ale VŽDY lokalizovaná podle `Accept-Language` (`AcceptLanguageInterceptor`),
 * takže i neznámý kód appka zobrazí správným jazykem, jen bez appce vlastního doladění textu.
 */
fun Throwable.toUiText(): UiText = when (this) {
  is GraphQlAppException -> UiText.Raw(serverMessage)
  is HttpAppException -> UiText.Raw(serverMessage)
  else -> UiText.Res(R.string.error_generic)
}
