package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay

private val DEFAULT_CENTER = GeoPoint(49.8, 15.5) // střed ČR — bez zadaného bodu
private const val DEFAULT_ZOOM = 7.0
private const val POINT_ZOOM = 16.0

/**
 * Mapa nad OpenStreetMap (osmdroid) — dva režimy: náhled (souřadnice se jen zobrazí) a výběr
 * bodu (klepnutí do mapy nebo přetažení značky). Dlaždice (tile.openstreetmap.org) se stahují
 * PŘÍMO Z KLIENTA — vědomá výjimka z pravidla "jen ze serveru" (docs/soukromi.md,
 * geocodeAddress/reverseGeocode jdou vždy přes backend). Zmírněno tím, že se mapa (a tedy
 * i stahování dlaždic) vytvoří až po klepnutí na "Zobrazit mapu", nikdy automaticky. Webový
 * protějšek: frontend shared/location-map.ts (Leaflet).
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
      Button(onClick = { shown = true }) { Text("Zobrazit mapu") }
    } else {
      MapContent(lat = lat, lon = lon, editable = editable, onPointSelected = onPointSelected)
      if (editable) {
        Text(
          "Klepni do mapy nebo přetáhni značku na přesné místo.",
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
  val mapView = remember {
    MapView(context).apply {
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)
    }
  }
  val marker = remember { Marker(mapView) }

  DisposableEffect(Unit) {
    onDispose { mapView.onDetach() }
  }

  AndroidView(
    modifier = Modifier.fillMaxWidth().height(240.dp),
    factory = {
      val hasPoint = lat != null && lon != null
      mapView.controller.setZoom(if (hasPoint) POINT_ZOOM else DEFAULT_ZOOM)
      mapView.controller.setCenter(if (hasPoint) GeoPoint(lat, lon) else DEFAULT_CENTER)

      marker.setInfoWindow(null) // appka nemá popisek k zobrazení, jen bod samotný
      marker.isDraggable = editable
      if (hasPoint) {
        marker.position = GeoPoint(lat, lon)
        mapView.overlays.add(marker)
      }
      if (editable) {
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
          override fun onMarkerDrag(marker: Marker) = Unit
          override fun onMarkerDragStart(marker: Marker) = Unit
          override fun onMarkerDragEnd(marker: Marker) {
            onPointSelected?.invoke(marker.position.latitude, marker.position.longitude)
          }
        })
        val receiver = object : MapEventsReceiver {
          override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
            marker.position = point
            if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
            mapView.invalidate()
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
      // Souřadnice se po vytvoření mapy dál mohou měnit zvenčí (reverseGeocode, "Použít mou
      // polohu") — přesuň značku, mapu samotnou tím neinicializujeme znovu.
      if (lat != null && lon != null) {
        val point = GeoPoint(lat, lon)
        marker.position = point
        if (!view.overlays.contains(marker)) view.overlays.add(marker)
        view.invalidate()
      }
    },
  )
}
