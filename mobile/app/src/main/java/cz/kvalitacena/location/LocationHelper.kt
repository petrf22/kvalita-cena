package cz.kvalitacena.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val LOCATION_TIMEOUT_MS = 15_000L

/**
 * Obyčejný LocationManager, ne Play Services Fused Location — appka má běžet i bez Google
 * Play Services (stejný důvod jako ZXing místo ML Kit, viz plán projektu). Poloha se použije
 * jen jako parametr dotazu na nejbližší obchody a nikam se neukládá (docs/soukromi.md
 * v backendu) — volající si výsledek (seznam obchodů) uloží, souřadnice zahodí.
 */
@SuppressLint("MissingPermission") // volající musí mít ACCESS_COARSE_LOCATION už povolené
suspend fun getCurrentLocation(context: Context): Location? = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
  suspendCancellableCoroutine { cont ->
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Poptáme oba providery zároveň a použijeme první odpověď — spoléhat jen na
    // NETWORK_PROVIDER je nespolehlivé (na řadě zařízení i v emulátoru je "enabled", ale
    // fix nikdy nedodá), a requestSingleUpdate sám o sobě nemá timeout, takže by appka
    // bez withTimeoutOrNull výše mohla čekat donekonečna beze zpětné vazby.
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      .filter { locationManager.isProviderEnabled(it) }

    if (providers.isEmpty()) {
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
    providers.forEach { locationManager.requestSingleUpdate(it, listener, Looper.getMainLooper()) }

    cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
  }
}
