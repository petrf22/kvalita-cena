package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Store

/**
 * Popisek obchodu pro dropdowny a seznamy — název (+ ulice, je-li vyplněná) a město, ve
 * stejném duchu jako web (frontend price-entry). Kód země se přilepí na konec JEN když se liší
 * od [homeCountry] (docs/lokalizace.md, "Country selector v UI") — český obchod se českému
 * uživateli ukáže beze změny, slovenský dostane "(SK)". Webový protějšek: shared/store-label.ts.
 * Čistá funkce mimo Compose, ať jde otestovat JUnitem bez instrumentace (stejný vzor jako
 * PriceChartGeometry).
 */
fun storeLabel(store: Store, homeCountry: String?): String {
  val nameAndStreet = listOfNotNull(
    store.name.trim().takeIf { it.isNotEmpty() },
    store.street?.trim()?.takeIf { it.isNotEmpty() },
  ).joinToString(", ")
  val base = if (nameAndStreet.isEmpty()) store.city else "$nameAndStreet — ${store.city}"
  return if (store.country != homeCountry) "$base (${store.country})" else base
}
