package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.kvalitacena.R
import cz.kvalitacena.network.Store
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val SINGLE_STORE_ZOOM = 15.0

/**
 * Mapa se značkami obchodů — doplněk k [StorePicker] (SearchableDropdown + "Najít v okolí"),
 * které dosud jediné umožňovaly obchod vybrat (docs/stav-implementace.md, "Adresa/mapa
 * provozovny" — mapa předtím uměla jen zadání/náhled JEDNOHO bodu, viz [LocationMap]). Souřadnice
 * UŽIVATELE se do mapy vůbec nedostávají — appka jen ukáže obchody, které už zná
 * (searchStores/nearbyStores), výřez je z jejich bounding boxu, žádná vlastní poloha
 * (docs/soukromi.md). Stejné vrátkování "Zobrazit mapu" jako [LocationMap] — dlaždice se
 * nestahují, dokud si o to uživatel neřekne. Sdílí `createOsmMapView`/`MapLifecycleEffect`
 * s [LocationMap] (`ui/common/OsmMapView.kt`).
 */
@Composable
fun StoreMap(
  stores: List<Store>,
  selectedStoreId: String?,
  onSelect: (Store) -> Unit,
  homeCountry: String?,
  modifier: Modifier = Modifier,
) {
  val located = remember(stores) { stores.filter { it.lat != null && it.lon != null } }
  if (located.isEmpty()) return

  var shown by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    if (!shown) {
      Button(onClick = { shown = true }) { Text(stringResource(R.string.store_map_show)) }
    } else {
      MapContent(stores = located, selectedStoreId = selectedStoreId, homeCountry = homeCountry, onSelect = onSelect)
    }
  }
}

@Composable
private fun MapContent(
  stores: List<Store>,
  selectedStoreId: String?,
  homeCountry: String?,
  onSelect: (Store) -> Unit,
) {
  val context = LocalContext.current
  val mapView = remember { createOsmMapView(context) }
  MapLifecycleEffect(mapView)

  AndroidView(
    modifier = Modifier.fillMaxWidth().height(240.dp),
    factory = {
      centerOnStores(mapView, stores)
      rebuildMarkers(mapView, stores, selectedStoreId, homeCountry, onSelect)
      mapView
    },
    update = { view ->
      // Nabídka obchodů se může mezi kompozicemi změnit (nové "Najít v okolí", psaní do
      // našeptávače) — přestav značky, výřez nech beze změny, appka do něj sama nezasahuje
      // (na rozdíl od LocationMap appka tady žádný jeden bod zvenčí neposílá).
      rebuildMarkers(view, stores, selectedStoreId, homeCountry, onSelect)
    },
  )
}

private fun centerOnStores(mapView: MapView, stores: List<Store>) {
  val points = stores.map { GeoPoint(it.lat!!, it.lon!!) }
  if (points.size == 1) {
    // setZoom/setCenter jsou jen parametry projekce, žádné rozměry Viewu nepotřebují.
    mapView.controller.setZoom(SINGLE_STORE_ZOOM)
    mapView.controller.setCenter(points.first())
  } else {
    // zoomToBoundingBox počítá se skutečnou šířkou/výškou Viewu (getWidth/getHeight) — ve
    // factory{} má View ještě rozměr 0×0 (layout ještě neproběhl), takže by se spočítal
    // nesmyslně široký výřez (celý svět). addOnFirstLayoutListener počká, až Compose/Android
    // View skutečně změří a rozmístí.
    mapView.addOnFirstLayoutListener { _, _, _, _, _ -> mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false) }
  }
}

private fun rebuildMarkers(
  mapView: MapView,
  stores: List<Store>,
  selectedStoreId: String?,
  homeCountry: String?,
  onSelect: (Store) -> Unit,
) {
  mapView.overlays.clear()
  // Vybraný obchod přidán poslední = vykreslený navrch, ať jeho značka nezapadne pod ostatní.
  stores.sortedBy { it.id == selectedStoreId }.forEach { store ->
    mapView.overlays.add(
      Marker(mapView).apply {
        position = GeoPoint(store.lat!!, store.lon!!)
        title = storeLabel(store, homeCountry)
        setInfoWindow(null) // appka nemá popisek k zobrazení, klik rovnou vybere (viz LocationMap)
        setOnMarkerClickListener { _, _ -> onSelect(store); true }
      },
    )
  }
  mapView.invalidate()
}
