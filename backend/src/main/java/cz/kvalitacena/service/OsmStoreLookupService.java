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

  /**
   * Kolik metrů se přidá k hledanému okruhu jako kompenzace za zaokrouhlení polohy na 3
   * desetinná místa. Mřížka 0,001° je 111 m na šířce a ~71 m na délce (50° s. š.), takže se
   * střed hledání může posunout až o ~66 m — u okruhu 200 m třetina. Bez kompenzace by
   * uživateli vypadl obchod, ve kterém právě stojí. Klient seznam dofiltruje podle skutečné
   * vzdálenosti (docs/soukromi.md), takže navíc přišlých pár řádků nikdo neuvidí.
   */
  public static final double ROUNDING_SLACK_M = 70;

  private final OverpassPoiService overpassPoiService;
  private final GeocodingService geocodingService;
  private final StoreRepository storeRepository;
  private final CountryResolver countryResolver;

  /**
   * Provozovny z OSM v okolí bodu, bez těch, které appka ve vlastním katalogu už zná
   * ({@link OsmStoreCandidateFilter}) — jinak by uživatel založil duplicitu.
   */
  public OsmStoreCandidateResult nearby(double lat, double lon, double radiusKm, Long viewerId) {
    double roundedLat = Coordinates.round(lat, 3);
    double roundedLon = Coordinates.round(lon, 3);

    OsmStoreCandidateResult result = overpassPoiService.nearby(roundedLat, roundedLon, radiusKm, ROUNDING_SLACK_M);
    if (result.candidates().isEmpty()) {
      return result;
    }
    // Vlastní obchody se berou ze stejného okruhu jako nabídka, aby se poznala shoda i u
    // provozovny, kterou někdo založil ručně (a osm_ref tedy nemá). Okruh ale nestačí:
    // obchod bez souřadnic ani cizí PENDING v něm nejsou, a uq_store_identity by je přesto
    // srazil — proto ještě dotaz podle názvu a města kandidátů.
    Map<Long, Store> known = new LinkedHashMap<>();
    storeRepository.findNearby(roundedLat, roundedLon, radiusKm * 1000 + ROUNDING_SLACK_M, viewerId)
        .forEach(store -> known.put(store.getId(), store));
    List<String> names = result.candidates().stream().map(OsmStoreCandidate::name).toList();
    List<String> cities = result.candidates().stream().map(OsmStoreCandidate::city)
        .filter(city -> city != null && !city.isBlank()).toList();
    if (!cities.isEmpty()) {
      storeRepository.findIdentityCandidates(pgArray(names), pgArray(cities))
          .forEach(store -> known.putIfAbsent(store.getId(), store));
    }
    String fallbackCountry = countryResolver.resolve(null, viewerId);
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
    if (city == null || city.isBlank()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.OK, List.of(), attribution);
    }
    Optional<List<GeocodeCandidate>> points = geocodingService.lookup(blankToNull(street), city.trim(),
        blankToNull(postalCode), countryResolver.resolve(country, viewerId));
    if (points.isEmpty()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.UNAVAILABLE, List.of(), attribution);
    }
    if (points.get().isEmpty()) {
      return new OsmStoreCandidateResult(OsmLookupStatus.OK, List.of(), attribution);
    }
    // Nominatim řadí podle relevance — první bod je nejlepší shoda s adresou.
    GeocodeCandidate point = points.get().getFirst();
    OsmStoreCandidateResult result = nearby(point.lat(), point.lon(), radiusKm, viewerId);
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
