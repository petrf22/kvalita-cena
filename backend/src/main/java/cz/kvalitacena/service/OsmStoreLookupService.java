package cz.kvalitacena.service;

import cz.kvalitacena.controller.GeocodeCandidate;
import cz.kvalitacena.controller.OsmLookupStatus;
import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.controller.OsmStoreCandidateResult;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nabídka provozoven z OpenStreetMap k založení obchodu (docs/datovy-model.md, „Oddělení
 * schémat kvůli ODbL"): kolem polohy (nearbyOsmStores — uživatel stojí v obchodě) a kolem
 * adresy (osmStoresAtAddress — obchod z hlavičky účtenky, kterou uživatel zpracovává doma).
 * Nic se z odpovědi neukládá; do core.store se dostane jen kandidát potvrzený ve formuláři.
 */
@Service
@RequiredArgsConstructor
public class OsmStoreLookupService {

  private final OverpassPoiService overpassPoiService;
  private final GeocodingService geocodingService;
  private final StoreRepository storeRepository;
  private final CountryResolver countryResolver;

  /**
   * Provozovny z OSM v okolí bodu, bez těch, které appka ve vlastním katalogu už zná
   * ({@link OsmStoreCandidateFilter}) — jinak by uživatel založil duplicitu.
   */
  public OsmStoreCandidateResult nearby(double lat, double lon, double radiusKm, Long viewerId) {
    return nearby(lat, lon, radiusKm, viewerId, countryResolver.resolve(null, viewerId));
  }

  /**
   * {@code fallbackCountry} je země, ve které se hledalo — kandidát z OSM bez addr:country
   * se s katalogem porovná v ní, jinak by se známý obchod v jiné zemi než v účtu nepoznal.
   */
  private OsmStoreCandidateResult nearby(double lat, double lon, double radiusKm, Long viewerId,
      String fallbackCountry) {
    double roundedLat = Coordinates.round(lat, 3);
    double roundedLon = Coordinates.round(lon, 3);

    OsmStoreCandidateResult result = overpassPoiService.nearby(roundedLat, roundedLon, radiusKm, Coordinates.ROUNDING_SLACK_M);
    if (result.candidates().isEmpty()) {
      return result;
    }
    // Vlastní obchody se berou ze stejného okruhu jako nabídka, aby se poznala shoda i u
    // provozovny, kterou někdo založil ručně (a osm_ref tedy nemá). Okruh ale nestačí:
    // obchod bez souřadnic ani cizí PENDING v něm nejsou, a uq_store_identity by je přesto
    // srazil — proto ještě dotaz podle názvu a města kandidátů.
    Map<Long, Store> known = new LinkedHashMap<>();
    storeRepository.findNearby(roundedLat, roundedLon, radiusKm * 1000 + Coordinates.ROUNDING_SLACK_M, viewerId)
        .forEach(store -> known.put(store.getId(), store));
    List<String> names = result.candidates().stream().map(OsmStoreCandidate::name).toList();
    List<String> cities = result.candidates().stream().map(OsmStoreCandidate::city)
        .filter(city -> city != null && !city.isBlank()).toList();
    if (!cities.isEmpty()) {
      storeRepository.findIdentityCandidates(pgArray(names), pgArray(cities))
          .forEach(store -> known.putIfAbsent(store.getId(), store));
    }
    return new OsmStoreCandidateResult(result.status(),
        OsmStoreCandidateFilter.withoutKnownStores(result.candidates(), List.copyOf(known.values()), fallbackCountry),
        result.attribution());
  }

  /**
   * Provozovny z OSM kolem adresy obchodu (typicky z hlavičky účtenky). Adresa se geokóduje
   * přes Nominatim a kolem nalezeného bodu se hledá jako v {@link #nearby}; kandidáti, jejichž
   * název sdílí slovo s {@code name}, jdou nahoru ({@link OsmStoreCandidateFilter#rankByName}).
   * Výpadek geokodéru je {@code UNAVAILABLE}, neznámá adresa {@code OK} s prázdným seznamem —
   * stejné rozlišení jako u Overpassu, ať klient při výpadku nenabádá k založení duplicity.
   */
  public OsmStoreCandidateResult atAddress(String name, String street, String city, String postalCode,
      String country, double radiusKm, Long viewerId) {
    String attribution = geocodingService.attribution();
    // Bez ulice by Nominatim vrátil střed obce a nabídka by ukázala náhodné obchody z centra
    // jako obchody „u adresy z účtenky" — to je horší než žádná nabídka.
    if (city == null || city.isBlank() || street == null || street.isBlank()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.OK, List.of(), attribution);
    }
    String resolvedCountry = countryResolver.resolve(country, viewerId);
    Optional<List<GeocodeCandidate>> points = geocodingService.lookup(street.trim(), city.trim(),
        blankToNull(postalCode), resolvedCountry);
    if (points.isEmpty()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.UNAVAILABLE, List.of(), attribution);
    }
    if (points.get().isEmpty()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.OK, List.of(), attribution);
    }
    // Nominatim řadí podle relevance — první bod je nejlepší shoda s adresou.
    GeocodeCandidate point = points.get().getFirst();
    OsmStoreCandidateResult result = nearby(point.lat(), point.lon(), radiusKm, viewerId, resolvedCountry);
    return new OsmStoreCandidateResult(result.status(),
        OsmStoreCandidateFilter.rankByName(result.candidates(), name), result.attribution());
  }

  /** Literál pole PostgreSQL ({@code {"a","b"}}) pro {@code CAST(:x AS TEXT[])} v nativním dotazu. */
  static String pgArray(List<String> values) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return sb.append('}').toString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
