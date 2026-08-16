package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import cz.kvalitacena.R

/**
 * Tvar identifikačního čísla firmy per zemi (docs/lokalizace.md) — zrcadlí web
 * `shared/store-form.ts` (COMPANY_ID_DIGITS/companyIdLabel). Appka jen tvar nekontroluje
 * (ne kontrolní součet, aby neduplikovala CompanyIdValidator na backendu); země bez záznamu
 * se tvarem nekontroluje vůbec. Plán expanze (2026-08) přidal HR/SI/RS/FR/IT (mají skutečný
 * CompanyIdValidator na backendu) — zbylých 8 nových zemí zatím validátor nemá, popisek ale
 * mají všechny, aby appka nezobrazovala cizí "IČO" u obchodu v Německu/Francii/atd.
 */
private val COMPANY_ID_DIGITS = mapOf(
  "CZ" to 8, "SK" to 8, "PL" to 10, "HR" to 11, "SI" to 8, "RS" to 9, "FR" to 9, "IT" to 11,
)
private val COUNTRIES_WITH_REGISTRY = setOf("CZ")

fun companyIdDigits(country: String?): Int? = COMPANY_ID_DIGITS[country]

fun hasCompanyRegistry(country: String?): Boolean = country in COUNTRIES_WITH_REGISTRY

@StringRes
fun companyIdLabelRes(country: String?): Int = when (country) {
  "PL" -> R.string.company_id_label_pl
  "DE" -> R.string.company_id_label_de
  "AT" -> R.string.company_id_label_at
  "FR" -> R.string.company_id_label_fr
  "ES" -> R.string.company_id_label_es
  "IT" -> R.string.company_id_label_it
  "HR" -> R.string.company_id_label_hr
  "SI" -> R.string.company_id_label_si
  "BG" -> R.string.company_id_label_bg
  "HU" -> R.string.company_id_label_hu
  "RO" -> R.string.company_id_label_ro
  "GB" -> R.string.company_id_label_gb
  "CH" -> R.string.company_id_label_ch
  "RS" -> R.string.company_id_label_rs
  else -> R.string.company_id_label_generic
}
