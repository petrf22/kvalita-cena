package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import cz.kvalitacena.R
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay

private val DEFAULT_CENTER = GeoPoint(49.8, 15.5) // střed ČR — bez zadaného bodu
private const val DEFAULT_ZOOM = 7.0
private const val POINT_ZOOM = 16.0
private const val ANIMATION_MS = 500L

/**
 * Mapa nad OpenStreetMap (osmdroid) — dva režimy: náhled (souřadnice se jen zobrazí) a výběr
 * bodu (klepnutí do mapy, přetažení značky, nebo tlačítko "Na mou polohu"). Dlaždice
 * (tile.openstreetmap.org) se stahují PŘÍMO Z KLIENTA — vědomá výjimka z pravidla "jen ze
 * serveru" (docs/soukromi.md, geocodeAddress/reverseGeocode jdou vždy přes backend). Zmírněno
 * tím, že se mapa (a tedy i stahování dlaždic) vytvoří až po klepnutí na "Zobrazit mapu", nikdy
 * automaticky. "Na mou polohu" tohle zostřuje o kousek víc — vycentruje mapu přímo na uživatele,
 * takže dlaždice prozradí okolí přesněji než dřív (docs/soukromi.md). Webový protějšek:
 * frontend shared/location-map.ts (Leaflet).
 *
 * Sdílí `createOsmMapView`/`MapLifecycleEffect`/`MyLocationButton` s [StoreMap]
 * (`ui/common/OsmMapView.kt`).
 */
@Composable
fun LocationMap(
  lat: Double?,
  lon: Double?,
  editable: Boolean,
  onPointSelected: ((Double, Double) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  var shown by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    if (!shown) {
      Button(onClick = { shown = true }) { Text(stringResource(R.string.map_show)) }
    } else {
      MapContent(lat = lat, lon = lon, editable = editable, onPointSelected = onPointSelected)
      if (editable) {
        Text(
          stringResource(R.string.map_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun MapContent(
  lat: Double?,
  lon: Double?,
  editable: Boolean,
  onPointSelected: ((Double, Double) -> Unit)?,
) {
  val context = LocalContext.current
  val mapView = remember { createOsmMapView(context) }
  val marker = remember { Marker(mapView) }
  MapLifecycleEffect(mapView)

  // Poslední bod, na který appka mapu sama vycentrovala/o kterém ví, že ho způsobil dotyk
  // uvnitř mapy (klepnutí, tažení) — když se lat/lon změní ZVENČÍ (reverseGeocode, "Použít
  // mou polohu", "Na mou polohu"), mapa se má přesunout za značkou; když je to jen ozvěna
  // vlastního dotyku, přecentrování by mapu zbytečně "škublo" pod prstem.
  var lastKnownPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }

  Column {
    AndroidView(
      modifier = Modifier.fillMaxWidth().height(240.dp),
      factory = {
        val hasPoint = lat != null && lon != null
        val initialPoint = if (hasPoint) GeoPoint(lat, lon) else DEFAULT_CENTER
        mapView.controller.setZoom(if (hasPoint) POINT_ZOOM else DEFAULT_ZOOM)
        mapView.controller.setCenter(initialPoint)
        lastKnownPoint = if (hasPoint) lat to lon else null

        marker.setInfoWindow(null) // appka nemá popisek k zobrazení, jen bod samotný
        marker.isDraggable = editable
        if (hasPoint) {
          marker.position = initialPoint
          mapView.overlays.add(marker)
        }
        if (editable) {
          marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
              lastKnownPoint = marker.position.latitude to marker.position.longitude
              onPointSelected?.invoke(marker.position.latitude, marker.position.longitude)
            }
          })
          val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
              marker.position = point
              if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
              mapView.invalidate()
              lastKnownPoint = point.latitude to point.longitude
              onPointSelected?.invoke(point.latitude, point.longitude)
              return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean = false
          }
          mapView.overlays.add(MapEventsOverlay(receiver))
        }
        mapView
      },
      update = { view ->
        // Souřadnice se po vytvoření mapy dál mohou měnit zvenčí — přesuň značku a mapu za ní
        // přecentruj, JEN když appka sama nevěděla, že tenhle bod už zobrazuje (lastKnownPoint).
        if (lat != null && lon != null) {
          val point = GeoPoint(lat, lon)
          marker.position = point
          if (!view.overlays.contains(marker)) view.overlays.add(marker)
          view.invalidate()
          if (lastKnownPoint != lat to lon) {
            lastKnownPoint = lat to lon
            view.controller.animateTo(point, POINT_ZOOM, ANIMATION_MS)
          }
        }
      },
    )
    if (editable) {
      MyLocationButton(
        onLocationResolved = { newLat, newLon ->
          val point = GeoPoint(newLat, newLon)
          marker.position = point
          if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
          mapView.invalidate()
          mapView.controller.animateTo(point, POINT_ZOOM, ANIMATION_MS)
          lastKnownPoint = newLat to newLon
          onPointSelected?.invoke(newLat, newLon)
        },
      )
    }
  }
}
