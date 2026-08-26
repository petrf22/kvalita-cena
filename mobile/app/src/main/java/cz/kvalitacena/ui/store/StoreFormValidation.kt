package cz.kvalitacena.ui.store

import cz.kvalitacena.ui.common.companyIdDigits

/**
 * Čistá validace formuláře nového obchodu — mimo Compose, ať jde otestovat JUnitem (stejný
 * vzor jako PriceChartGeometry). Kontrolní součet IČO/NIP se tu NEDUPLIKUJE (viz plán projektu)
 * — kontroluje se jen tvar (počet číslic per zemi, docs/lokalizace.md — CompanyId.kt), skutečný
 * kontrolní součet ověří až server (CompanyIdValidator) a volitelně ARES; klient nemá být druhý
 * zdroj pravdy pro totéž pravidlo.
 */

fun isStoreFormValid(name: String, city: String): Boolean =
  name.isNotBlank() && city.isNotBlank()

/** Země bez záznamu v [companyIdDigits] = appka tvar nekontroluje vůbec (viz backend). */
fun isIcoShapeValid(ico: String, country: String? = "CZ"): Boolean {
  if (ico.isBlank()) return true
  val digits = companyIdDigits(country) ?: return true
  return ico.matches(Regex("^\\d{$digits}$"))
}

/** Jen tvar (http/https + host) — appka odkaz nikdy nenačítá, viz backend UrlValidation. */
fun isUrlShapeValid(url: String): Boolean {
  if (url.isBlank()) return true
  val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
  val scheme = uri.scheme?.lowercase()
  return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
