package cz.kvalitacena.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.kvalitacena.R
import cz.kvalitacena.location.getCurrentLocation
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * Sdílený osmdroid boilerplate pro [LocationMap] (jeden bod, editovatelný) i [StoreMap] (značky
 * obchodů) — vytváří se jednou při refaktoru obou (docs/stav-implementace.md, "Adresa/mapa
 * provozovny"). Předtím byl duplikovaný jen v LocationMap a mapa se dala jen zobrazit/klepnout,
 * ne posouvat prstem ani zaostřit na vlastní polohu.
 */

/** MapView s dlaždicemi OSM a opravou dotyku — bez touch listeneru níž mapa uvnitř `verticalScroll`
 *  nešla vůbec posunout ani přiblížit, Compose scroll gesto ho sebralo dřív, než se dostalo k mapě. */
@SuppressLint("ClickableViewAccessibility")
fun createOsmMapView(context: Context): MapView =
  MapView(context).apply {
    // Bez tohohle se po skrytí/zobrazení klávesnice (IME resize) uvnitř verticalScroll Column
    // vložený nativní MapView někdy vykreslí na starém místě přes okolní Compose obsah —
    // hardwarová vrstva si drží zastaralý bitmap, softwarové vykreslování vynutí přerýsování
    // na aktuální pozici při každém průchodu (známá interop chyba AndroidView + scroll).
    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    setTileSource(MapConfig.TILE_SOURCE)
    setMultiTouchControls(true)
    setOnTouchListener { view, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
      }
      false // dál to zpracuje osmdroid samo (posun/zoom/klik) — jen jsme si vyprosili dotyk od rodiče
    }
    // ODbL/tile usage policy vyžadují viditelnou atribuci poskytovatele dlaždic v samotné mapě
    // — dřív appka měla jen text v „O aplikaci". Overlay čte copyright přímo z aktuálního
    // tile source, takže se s výměnou poskytovatele (KVALITACENA_MAP_TILE_*) mění sám.
    overlays.add(CopyrightOverlay(context))
  }

/** `mapView.onResume()`/`onPause()` podle lifecycle — bez nich se mapa na části zařízení vykreslí prázdná. */
@Composable
fun MapLifecycleEffect(mapView: MapView) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, mapView) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> mapView.onResume()
        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      mapView.onDetach()
    }
  }
}

/**
 * Tlačítko "Na mou polohu" — jednorázový [getCurrentLocation] (LocationManager, ne průběžný
 * odběr přes MyLocationNewOverlay, viz docs/soukromi.md), stejný vzor jako "Použít mou polohu"
 * ve StoreFormScreen/"Najít v okolí" v PriceEntryScreen. Volající si polohu vycentruje sám
 * (`onLocationResolved`), tahle komponenta jen řeší oprávnění a načtení.
 */
@Composable
fun MyLocationButton(onLocationResolved: (Double, Double) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var locating by remember { mutableStateOf(false) }
  var unavailable by remember { mutableStateOf(false) }

  fun locate() {
    locating = true
    unavailable = false
    scope.launch {
      val location = getCurrentLocation(context)
      if (location != null) onLocationResolved(location.latitude, location.longitude)
      else unavailable = true
      locating = false
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> if (granted) locate() else locating = false }

  Column(modifier = modifier) {
    OutlinedButton(
      onClick = {
        val hasPermission = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) locate() else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
      },
    ) {
      if (locating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text(stringResource(R.string.map_my_location))
    }
    if (unavailable) {
      Text(
        stringResource(R.string.map_location_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}
