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

  @Composable
  fun asString(): String = when (this) {
    is Res -> stringResource(id, *args.toTypedArray())
    is Plural -> pluralStringResource(id, count, *args.toTypedArray())
    is Raw -> value
  }
}
