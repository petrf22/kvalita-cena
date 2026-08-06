package cz.kvalitacena.ui.common

/**
 * Jediný zdroj popisků druhu ceny — dřív byla tahle mapa duplicitně ve dvou souborech
 * (PriceEntryScreen, ProductDetailScreen) a lišily se (jedna neznala MULTIBUY).
 */
val PRICE_KIND_LABELS = mapOf(
  "REGULAR" to "Běžná cena",
  "PROMO" to "Akce",
  "CLUB_CARD" to "Klubová karta",
  "CLEARANCE" to "Výprodej",
  "MULTIBUY" to "Množstevní sleva",
)
