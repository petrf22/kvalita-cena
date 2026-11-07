import type { GeocodeCandidate, OsmStoreCandidate } from '../models/catalog';
import { DataOrigin, GeoSource } from '../models/generated/enums';

/** Adresa obchodu, kolem které se v OSM hledalo — dnes vždy hlavička účtenky. */
export interface StoreAddress {
  street: string | null;
  postalCode: string | null;
  city: string | null;
}

/** Čím kandidát z OpenStreetMap vyplní formulář nového obchodu. */
export interface OsmStoreFormValues {
  name: string;
  street: string;
  city: string;
  postalCode: string;
  /** `null` = OSM zemi nenese, nebo ji appka nezná; formulář pak nechá svou (volbu vieweru). */
  country: string | null;
  lat: number;
  lon: number;
}

/**
 * Předvyplnění z kandidáta OSM — protějšek mobilního `StoreFormViewModel.applyOsmCandidate`.
 * Název a adresa jdou z OSM celé, i s chybějícími částmi (mobil: `orEmpty()`), aby nevznikla
 * smíchaná, ale zdánlivě platná adresa ze dvou zdrojů.
 *
 * Jediná odchylka od mobilu: `addr:city` v OSM u provozoven často chybí a formulář by bez města
 * nešel uložit. Mobil to neřeší, protože tam uživatel stojí v obchodě a formulář nemá z čeho
 * brát. U účtenky ale víme, kolem jaké adresy se hledalo — pak se vezme CELÁ tahle adresa
 * (`fallbackAddress`), ne jen město. Jsou to vlastní data uživatele, takže na značce původu
 * `OSM_DERIVED` (název je pořád z OSM) to nic nemění.
 *
 * Země se převezme jen z `supportedCountries` (číselník formuláře) — jinak by výběr ukazoval kód,
 * který server odmítne (`UNSUPPORTED_COUNTRY`), a obchod by dostal cizí měnu.
 */
export function osmCandidateFormValues(
  candidate: OsmStoreCandidate,
  fallbackAddress: StoreAddress | null,
  supportedCountries: readonly string[],
): OsmStoreFormValues {
  const fromOsm = {
    street: candidate.street?.trim() ?? '',
    postalCode: candidate.postalCode?.trim() ?? '',
    city: candidate.city?.trim() ?? '',
  };
  const fallback = {
    street: fallbackAddress?.street?.trim() ?? '',
    postalCode: fallbackAddress?.postalCode?.trim() ?? '',
    city: fallbackAddress?.city?.trim() ?? '',
  };
  const address = fromOsm.city || !fallback.city ? fromOsm : fallback;
  const country = candidate.country?.trim().toUpperCase() ?? null;
  return {
    name: candidate.name.trim(),
    ...address,
    country: country && supportedCountries.includes(country) ? country : null,
    lat: candidate.lat,
    lon: candidate.lon,
  };
}

/** Bod formuláře je pořád ten, který přinesl kandidát z OSM (uživatel ho na mapě neposunul). */
export function isOsmPoint(
  osm: Pick<OsmStoreCandidate, 'lat' | 'lon'> | null,
  manualLat: number | null,
  manualLon: number | null,
): boolean {
  return osm != null && manualLat === osm.lat && manualLon === osm.lon;
}

export interface StoreGeoState {
  /** Kandidát z geokódování adresy („Najít souřadnice") — z OSM je jen BOD. */
  geocodeCandidate: Pick<GeocodeCandidate, 'lat' | 'lon' | 'osmRef'> | null;
  /** Provozovna převzatá z nabídky OSM — z OSM je i název a adresa. */
  osmCandidate: Pick<OsmStoreCandidate, 'lat' | 'lon' | 'osmRef'> | null;
  manualLat: number | null;
  manualLon: number | null;
}

export interface StoreGeoFields {
  lat: number | null;
  lon: number | null;
  geoSource: GeoSource | null;
  osmRef: string | null;
  /** `null` = server dosadí OWN. */
  dataOrigin: DataOrigin | null;
}

/**
 * Bod a značky původu do `CreateStoreInput` — přesně podle mobilního `StoreFormViewModel.submit`.
 *
 * `geoSource` je původ BODU, `dataOrigin` původ TEXTU (docs/datovy-model.md, pravidlo 1): u
 * převzaté provozovny může uživatel bod posunout na mapě, pak je bod jeho (`COMMUNITY`), ale
 * název a adresa zůstávají z OSM, takže `OSM_DERIVED` platí dál — i když je pak ještě upravil,
 * jde o to, ODKUD data pocházejí. `osmRef` u `OSM_DERIVED` ukazuje vždy na obchod, ne na
 * geokódovaný bod, jinak by se značka původu odkazovala na cizí záznam. Samotné geokódování
 * adresy `OSM_DERIVED` není — odtamtud je jen bod, ne text.
 */
export function storeGeoFields(state: StoreGeoState): StoreGeoFields {
  const { geocodeCandidate: geocoded, osmCandidate: osm, manualLat, manualLon } = state;
  const lat = geocoded?.lat ?? manualLat;
  const lon = geocoded?.lon ?? manualLon;
  const pointFromOsm = geocoded != null || isOsmPoint(osm, manualLat, manualLon);
  return {
    lat,
    lon,
    geoSource: pointFromOsm ? GeoSource.Osm : lat != null ? GeoSource.Community : null,
    osmRef: osm?.osmRef ?? geocoded?.osmRef ?? null,
    dataOrigin: osm ? DataOrigin.OsmDerived : null,
  };
}

/** Druhý řádek kandidáta v nabídce — ulice a město, bez vzdálenosti (ta je od adresy, ne od uživatele). */
export function osmCandidateAddress(candidate: OsmStoreCandidate): string {
  return [candidate.street, candidate.city]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(' · ');
}
