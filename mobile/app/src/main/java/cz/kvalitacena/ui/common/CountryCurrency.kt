package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import cz.kvalitacena.R

/**
 * Zrcadlí `app.i18n.country-currency` na backendu a `frontend/src/app/shared/country-currency.ts`
 * (docs/lokalizace.md) — jen pro NÁPOVĚDU v UI (popisek pole ceny podle vybraného obchodu), samotné
 * rozhodnutí o měně dělá server (`CurrencyResolver.forStore`) nezávisle na tomhle. Drobná neshoda
 * by způsobila jen dočasně špatný popisek pole, nikdy špatně uloženou měnu.
 */
private val COUNTRY_CURRENCY = mapOf("CZ" to "CZK", "SK" to "EUR", "PL" to "PLN")

fun currencyForCountry(country: String?): String = COUNTRY_CURRENCY[country] ?: "CZK"

/** Lokalizovaný název země pro výběr země (store-form, Nastavení) — appka zná jen CZ/SK/PL. */
@StringRes
fun countryNameRes(country: String): Int = when (country) {
  "SK" -> R.string.country_name_sk
  "PL" -> R.string.country_name_pl
  else -> R.string.country_name_cz
}
