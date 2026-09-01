/**
 * Poskytovatel mapových dlaždic pro `location-map.ts` — jedno místo, ne literál rovnou v
 * komponentě, ať jde výměna poskytovatele udělat jedním commitem. Mobilní protějšek:
 * `ui/common/MapConfig.kt` (tam je konfigurovatelný přes Gradle property, web runtime
 * konfiguraci nemá — viz komentář tam).
 *
 * Atribuce DLAŽDIC (poskytovatel renderu) je jiná věc než atribuce DAT (OSM/ODbL, souřadnice
 * provozoven, geokódování — `public/i18n/about/*.json` `dataSourcesOsm`). Výměna tile serveru
 * nad stejnými OSM daty atribuci dat nemění, jen tuhle.
 */
export const MAP_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
export const MAP_TILE_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
export const MAP_TILE_MAX_ZOOM = 19;
