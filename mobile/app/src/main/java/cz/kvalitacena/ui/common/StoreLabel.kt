package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Store

/**
 * Popisek obchodu pro dropdowny a seznamy — název (+ ulice, je-li vyplněná) a město, ve
 * stejném duchu jako web (frontend price-entry). Čistá funkce mimo Compose, ať jde otestovat
 * JUnitem bez instrumentace (stejný vzor jako PriceChartGeometry).
 */
fun storeLabel(store: Store): String {
  val nameAndStreet = listOfNotNull(
    store.name.trim().takeIf { it.isNotEmpty() },
    store.street?.trim()?.takeIf { it.isNotEmpty() },
  ).joinToString(", ")
  return if (nameAndStreet.isEmpty()) store.city else "$nameAndStreet — ${store.city}"
}
