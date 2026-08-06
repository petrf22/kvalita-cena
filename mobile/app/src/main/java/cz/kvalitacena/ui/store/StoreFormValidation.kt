package cz.kvalitacena.ui.store

/**
 * Čistá validace formuláře nového obchodu — mimo Compose, ať jde otestovat JUnitem (stejný
 * vzor jako PriceChartGeometry). Kontrolní součet IČO se tu NEDUPLIKUJE (viz plán projektu) —
 * kontroluje se jen tvar (8 číslic), skutečný kontrolní součet ověří až server (IcoValidator)
 * a volitelně ARES; klient nemá být druhý zdroj pravdy pro totéž pravidlo.
 */

fun isStoreFormValid(name: String, city: String): Boolean =
  name.isNotBlank() && city.isNotBlank()

fun isIcoShapeValid(ico: String): Boolean =
  ico.isBlank() || ico.matches(Regex("^\\d{8}$"))
