package cz.kvalitacena.ui.common

import cz.kvalitacena.R
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Vzdálenost obchodu od uživatele — počítá ji klient, ne server. Dva důvody:
 *
 * 1. Server dostává polohu zaokrouhlenou na ~110 m (docs/soukromi.md), takže by vzdálenost
 *    spočítal o desítky metrů vedle. Přesnou polohu zná jen telefon a nikdy ji neopouští.
 * 2. `nearbyStores` hledá s okruhem zvětšeným o chybu zaokrouhlení, takže v odpovědi můžou
 *    být i obchody kousek za hranicí toho, co si uživatel nastavil — dofiltrovat je umí zase
 *    jen klient.
 *
 * Vlastní haversine, ne `Location.distanceTo`: `android.location.Location` je frameworková
 * třída, která v JUnitu bez instrumentace nefunguje, a tahle logika je přesně ten druh čisté
 * logiky, který se podle `mobile/CLAUDE.md` testovat MÁ. Kulová aproximace se od elipsoidu
 * liší o desetiny procenta, což je na stovkách metrů hluboko pod chybou mobilního fixu.
 */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val dLat = Math.toRadians(lat2 - lat1)
  val dLon = Math.toRadians(lon2 - lon1)
  val a = sin(dLat / 2) * sin(dLat / 2) +
    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
  return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/**
 * Popisek vzdálenosti. Zaokrouhluje se schválně hrubě (na 10 m, nad kilometr na desetinu) —
 * vzdálenost stojí na poloze s přesností mobilního fixu, takže "137 m" by předstíralo přesnost,
 * kterou údaj nemá. Číslo se formátuje až v Compose podle jazyka (docs/lokalizace.md:
 * desetinná čárka v češtině, tečka v angličtině), proto UiText s argumentem, ne hotový string.
 */
fun distanceLabel(meters: Double): UiText = when {
  // Spodní hranice 10 m: "0 m" u obchodu, ve kterém člověk stojí, vypadá jako chybějící údaj,
  // a přesnost mobilního fixu je stejně horší.
  meters < 1000 -> UiText.Res(R.string.store_picker_distance_m, listOf(maxOf(10, (meters / 10).roundToInt() * 10)))
  else -> UiText.Res(R.string.store_picker_distance_km, listOf(roundToOneDecimal(meters / 1000)))
}

private fun roundToOneDecimal(value: Double): Double = (value * 10).roundToInt() / 10.0

/** Kilometry na metry pro porovnání s uživatelem nastaveným okruhem. */
fun isWithin(meters: Double, radiusMeters: Int): Boolean = meters <= radiusMeters + EPSILON_M

// Tolerance proti tomu, aby obchod přesně na hranici okruhu vypadl kvůli šumu v posledním bitu.
private const val EPSILON_M = 0.5
