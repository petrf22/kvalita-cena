package cz.kvalitacena.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Nastavení okolí neobsahuje polohu, jen souhlas s jejím jednorázovým použitím a okruh. */
class NearbySettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("kvalita_a_cena_settings", Context.MODE_PRIVATE)

  var radiusMeters by mutableStateOf(prefs.getInt("nearby_radius_meters", 500).coerceIn(100, 25_000))
    private set
  var useLocation by mutableStateOf(prefs.getBoolean("nearby_use_location", true))
    private set

  fun setRadius(value: Int) {
    require(value in 100..25_000)
    radiusMeters = value
    prefs.edit().putInt("nearby_radius_meters", value).apply()
  }

  fun setLocationEnabled(value: Boolean) {
    useLocation = value
    prefs.edit().putBoolean("nearby_use_location", value).apply()
  }
}
