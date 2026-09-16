package cz.kvalitacena.ui.settings

/** Stejný horní limit jako nearbyStores na serveru; desetinná čísla nejsou celé metry. */
fun parseNearbyRadius(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 100..25_000 }
