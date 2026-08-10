package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import cz.kvalitacena.R

/**
 * Tvar identifikačního čísla firmy per zemi (docs/lokalizace.md) — zrcadlí web
 * `shared/store-form.ts` (COMPANY_ID_DIGITS/companyIdLabel). Appka jen tvar nekontroluje
 * (ne kontrolní součet, aby neduplikovala CompanyIdValidator na backendu); země bez záznamu
 * se tvarem nekontroluje vůbec.
 */
private val COMPANY_ID_DIGITS = mapOf("CZ" to 8, "SK" to 8, "PL" to 10)
private val COUNTRIES_WITH_REGISTRY = setOf("CZ")

fun companyIdDigits(country: String?): Int? = COMPANY_ID_DIGITS[country]

fun hasCompanyRegistry(country: String?): Boolean = country in COUNTRIES_WITH_REGISTRY

@StringRes
fun companyIdLabelRes(country: String?): Int = if (country == "PL") {
  R.string.company_id_label_pl
} else {
  R.string.company_id_label_generic
}
