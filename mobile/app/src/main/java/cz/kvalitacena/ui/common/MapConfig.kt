package cz.kvalitacena.ui.common

import cz.kvalitacena.BuildConfig
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Poskytovatel mapových dlaždic pro [createOsmMapView] — konfigurovatelný přes
 * `KVALITACENA_MAP_TILE_URL`/`KVALITACENA_MAP_TILE_ATTRIBUTION` (mobile/app/build.gradle.kts),
 * fallback na dnešní OpenStreetMap Mapnik. Vlastní podtřída [OnlineTileSourceBase] místo
 * osmdroid vestavěného `XYTileSource` — ten bere jen base URL adresář, ne plnohodnotnou
 * `{z}/{x}/{y}` šablonu, kterou property nese. Webový protějšek: `shared/map-tiles.ts` (tam
 * bez runtime konfigurace, viz komentář tam).
 */
object MapConfig {
  val TILE_SOURCE: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "kvalitacena",
    0,
    BuildConfig.MAP_TILE_MAX_ZOOM,
    256,
    ".png",
    arrayOf(BuildConfig.MAP_TILE_URL),
    BuildConfig.MAP_TILE_ATTRIBUTION,
  ) {
    override fun getTileURLString(pMapTileIndex: Long): String {
      val zoom = MapTileIndex.getZoom(pMapTileIndex)
      val x = MapTileIndex.getX(pMapTileIndex)
      val y = MapTileIndex.getY(pMapTileIndex)
      return baseUrl
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
    }
  }
}
