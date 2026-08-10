package cz.kvalitacena.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Text, který se z ViewModelu/síťové vrstvy jen odloží a lokalizuje až v Compose (docs/lokalizace.md).
 * `stringResource` je `@Composable`, takže ho ViewModel volat nemůže; kdyby ViewModel volal
 * `context.getString()` rovnou, text by po přepnutí jazyka zůstal viset ve starém znění (appka
 * ho tahá jen jednou, ne při každé rekompozici). Mobilní protějšek webového `GraphQlAppError` +
 * `error-message.ts` — [Res]/[Plural] jsou obdoba klientského překladu podle klíče, [Raw]
 * obdoba zprávy ze serveru, která je podle `Accept-Language` už lokalizovaná.
 */
sealed interface UiText {
  data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
  data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
  /** Text, který je už hotový (např. serverMessage z GraphQL chyby) — appka ho jen zobrazí. */
  data class Raw(val value: String) : UiText

  // Argument smí být i vnořený UiText (např. popisek IČO/NIP per zemi) — rozbalí se až tady,
  // ViewModel/síťová vrstva k Compose kontextu nemá přístup.
  @Composable
  private fun resolvedArgs(args: List<Any>): Array<Any> =
    args.map { if (it is UiText) it.asString() else it }.toTypedArray()

  @Composable
  fun asString(): String = when (this) {
    is Res -> stringResource(id, *resolvedArgs(args))
    is Plural -> pluralStringResource(id, count, *resolvedArgs(args))
    is Raw -> value
  }
}
