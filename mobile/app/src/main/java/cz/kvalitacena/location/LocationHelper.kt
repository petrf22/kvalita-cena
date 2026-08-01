package cz.kvalitacena.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Obyčejný LocationManager, ne Play Services Fused Location — appka má běžet i bez Google
 * Play Services (stejný důvod jako ZXing místo ML Kit, viz plán projektu). Poloha se použije
 * jen jako parametr dotazu na nejbližší obchody a nikam se neukládá (docs/soukromi.md
 * v backendu) — volající si výsledek (seznam obchodů) uloží, souřadnice zahodí.
 */
@SuppressLint("MissingPermission") // volající musí mít ACCESS_COARSE_LOCATION už povolené
suspend fun getCurrentLocation(context: Context): Location? = suspendCancellableCoroutine { cont ->
  val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  val provider = when {
    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
    else -> null
  }

  if (provider == null) {
    cont.resume(null)
    return@suspendCancellableCoroutine
  }

  val listener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      locationManager.removeUpdates(this)
      if (cont.isActive) cont.resume(location)
    }
  }

  @Suppress("DEPRECATION")
  locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

  cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
}
