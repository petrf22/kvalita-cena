package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import cz.kvalitacena.R

/**
 * Zrcadlí `app.i18n.country-currency` na backendu a `frontend/src/app/shared/country-currency.ts`
 * (docs/lokalizace.md) — jen pro NÁPOVĚDU v UI (popisek pole ceny podle vybraného obchodu), samotné
 * rozhodnutí o měně dělá server (`CurrencyResolver.forStore`) nezávisle na tomhle. Drobná neshoda
 * by způsobila jen dočasně špatný popisek pole, nikdy špatně uloženou měnu.
 */
private val COUNTRY_CURRENCY = mapOf(
  "CZ" to "CZK", "SK" to "EUR", "PL" to "PLN", "DE" to "EUR", "AT" to "EUR", "FR" to "EUR",
  "ES" to "EUR", "IT" to "EUR", "HR" to "EUR", "SI" to "EUR", "BG" to "EUR", "HU" to "HUF",
  "RO" to "RON", "GB" to "GBP", "CH" to "CHF", "RS" to "RSD",
)

fun currencyForCountry(country: String?): String = COUNTRY_CURRENCY[country] ?: "CZK"

/** Lokalizovaný název země pro výběr země (store-form, Nastavení) — plán expanze o 13 dalších zemí. */
@StringRes
fun countryNameRes(country: String): Int = when (country) {
  "SK" -> R.string.country_name_sk
  "PL" -> R.string.country_name_pl
  "DE" -> R.string.country_name_de
  "AT" -> R.string.country_name_at
  "FR" -> R.string.country_name_fr
  "ES" -> R.string.country_name_es
  "IT" -> R.string.country_name_it
  "HR" -> R.string.country_name_hr
  "SI" -> R.string.country_name_si
  "BG" -> R.string.country_name_bg
  "HU" -> R.string.country_name_hu
  "RO" -> R.string.country_name_ro
  "GB" -> R.string.country_name_gb
  "CH" -> R.string.country_name_ch
  "RS" -> R.string.country_name_rs
  else -> R.string.country_name_cz
}
