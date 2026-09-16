package cz.kvalitacena.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.kvalitacena.R

private const val PREFS_NAME = "kvalita_a_cena_settings"
private const val KEY_RADIUS_M = "nearby_radius_m"

/**
 * Co pro uživatele znamená "v okolí". Výchozí 500 m odpovídá otázce "obchod, ve kterém právě
 * stojím" — appka se ptá na cenu z regálu, ne na obchody v celém městě. Škála je pár předvoleb,
 * ne posuvník: server polohu zaokrouhluje na ~110 m (docs/soukromi.md), takže jemnější krok
 * než 100 m nemá co rozlišit.
 *
 * Ukládá se v METRECH jako Int, ne v km jako Float — přesné a mapuje se 1:1 na předvolby.
 */
enum class NearbyRadius(val meters: Int, @StringRes val labelRes: Int) {
  M200(200, R.string.settings_nearby_radius_200m),
  M500(500, R.string.settings_nearby_radius_500m),
  KM1(1000, R.string.settings_nearby_radius_1km),
  KM3(3000, R.string.settings_nearby_radius_3km),
  KM5(5000, R.string.settings_nearby_radius_5km);

  val radiusKm: Double get() = meters / 1000.0
}

internal val DEFAULT_NEARBY_RADIUS = NearbyRadius.M500

/**
 * Uložená hodnota nemusí být platná předvolba — appka mohla škálu mezi verzemi změnit.
 * Nejbližší povolená je lepší odpověď než pád i než tichý návrat na výchozí hodnotu, protože
 * zachová, co uživatel zhruba chtěl.
 */
internal fun nearbyRadiusFromMeters(stored: Int): NearbyRadius {
  if (stored <= 0) return DEFAULT_NEARBY_RADIUS
  return NearbyRadius.entries.minBy { kotlin.math.abs(it.meters - stored) }
}

/**
 * Další preset nahoru pro akci "hledat v širším okolí" — `null` na stropu škály, kde už tlačítko
 * nemá co nabídnout a UI ho schová.
 */
internal fun widerNearbyRadius(current: NearbyRadius): NearbyRadius? =
  NearbyRadius.entries.firstOrNull { it.meters > current.meters }

/**
 * Okruh hledání obchodů v okolí. Stejný vzor jako [DisplayCurrencyStore]/[PriceEntryVisibilityStore]
 * — obyčejné SharedPreferences plus `mutableStateOf`, aby změnu Compose viděl bez restartu Activity.
 */
class NearbyRadiusStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var radius: NearbyRadius by mutableStateOf(
    nearbyRadiusFromMeters(prefs.getInt(KEY_RADIUS_M, DEFAULT_NEARBY_RADIUS.meters)),
  )
    private set

  fun select(value: NearbyRadius) {
    if (value == radius) return
    radius = value
    prefs.edit().putInt(KEY_RADIUS_M, value.meters).apply()
  }
}
