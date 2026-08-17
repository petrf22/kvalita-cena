package cz.kvalitacena.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Jazyky appky (docs/lokalizace.md) — endonyma se v přepínači zobrazují vlastním jménem. */
enum class AppLang(val tag: String, val endonym: String) {
  CS("cs", "Čeština"),
  SK("sk", "Slovenčina"),
  EN("en", "English"),
  PL("pl", "Polski"),
  DE("de", "Deutsch"),
}

/**
 * Přepínání jazyka přes `AppCompatDelegate.setApplicationLocales()` — na API 33+ deleguje na
 * systémový `LocaleManager` (in-app volba a systémový per-app picker jsou tak jeden a týž
 * stav), pod tím appka drží volbu sama (`autoStoreLocales` v manifestu). Dokud uživatel nic
 * nevybral, appka žádnou vlastní volbu nevynucuje — obyčejná resource fallback (`values/` =
 * čeština) se postará o zbytek, appka tak nepotřebuje protějšek k webovému
 * `readInitialLang()` (`navigator.languages` ∩ podporované → cs).
 *
 * Volba je zatím jen lokální (na rozdíl od webu zatím nepushuje na server přes `setLocale`
 * mutaci) — dotáhne se spolu s přepisem `GraphQlClient` na strukturované chyby (docs/lokalizace.md,
 * balík L), ať se `GraphQlClient` nemění dvakrát.
 */
object LocaleController {
  fun setLang(lang: AppLang) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang.tag))
  }
}

/** Aktuálně efektivní jazyk (systémový, nebo appkou vynucený) — pro zvýraznění v přepínači. */
@Composable
fun currentAppLang(): AppLang {
  val tag = LocalConfiguration.current.locales[0].language
  return AppLang.entries.firstOrNull { it.tag == tag } ?: AppLang.CS
}

/**
 * Necomposable ekvivalent [currentAppLang] — pro volání mimo Compose (např. při inicializaci
 * `CountryStore` nebo push `GraphQlClient.setLocale` po přepnutí země). `AppCompatDelegate`
 * samo o sobě Compose nevyžaduje, jen `currentAppLang()` kvůli `LocalConfiguration`.
 */
fun currentLangTag(): String =
  AppCompatDelegate.getApplicationLocales().get(0)?.language ?: Locale.getDefault().language
