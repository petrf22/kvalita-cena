package cz.kvalitacena.service;

import cz.kvalitacena.controller.GeocodeCandidate;
import cz.kvalitacena.controller.OsmLookupStatus;
import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.controller.OsmStoreCandidateResult;
import cz.kvalitacena.db.repo.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Nabídka z OSM kolem adresy z účtenky: výpadek geokodéru se nesmí tvářit jako „tady nic
 * není" (uživatel by zakládal duplicitu) a kandidáti podobní názvu z účtenky jdou nahoru.
 */
class OsmStoreLookupServiceTest {

  private final OverpassPoiService overpass = mock(OverpassPoiService.class);
  private final GeocodingService geocoding = mock(GeocodingService.class);
  private final StoreRepository stores = mock(StoreRepository.class);
  private final CountryResolver countries = mock(CountryResolver.class);
  private final OsmStoreLookupService service = new OsmStoreLookupService(overpass, geocoding, stores, countries);

  @BeforeEach
  void setUp() {
    when(geocoding.attribution()).thenReturn("© OpenStreetMap");
    when(countries.resolve(any(), any())).thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : "CZ");
    when(stores.findNearby(anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(List.of());
    when(stores.findIdentityCandidates(anyString(), anyString())).thenReturn(List.of());
  }

  @Test
  void geocoderOutageIsUnavailableAndSkipsOverpass() {
    when(geocoding.lookup(any(), any(), any(), any())).thenReturn(Optional.empty());

    OsmStoreCandidateResult result = service.atAddress("Albert", "Hlavní 1", "Brno", "602 00", null, 0.3, 1L);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.UNAVAILABLE);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.attribution()).isEqualTo("© OpenStreetMap");
    verifyNoInteractions(overpass);
  }

  @Test
  void unknownAddressIsAnEmptyOkResult() {
    when(geocoding.lookup(any(), any(), any(), any())).thenReturn(Optional.of(List.of()));

    OsmStoreCandidateResult result = service.atAddress("Albert", "Neexistující 1", "Brno", null, null, 0.3, 1L);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.OK);
    assertThat(result.candidates()).isEmpty();
    verifyNoInteractions(overpass);
  }

  @Test
  void searchesAroundGeocodedPointAndRanksByReceiptName() {
    when(geocoding.lookup(eq("Hlavní 1"), eq("Brno"), eq("602 00"), eq("CZ")))
        .thenReturn(Optional.of(List.of(new GeocodeCandidate(49.19523, 16.60801, "Hlavní 1, Brno", "way/9"))));
    OsmStoreCandidate lidl = new OsmStoreCandidate("Lidl", "Hlavní 2", "Brno", "60200", "CZ", 49.195, 16.608,
        "node/1", 40, null);
    OsmStoreCandidate albert = new OsmStoreCandidate("Albert", "Hlavní 1", "Brno", "60200", "CZ", 49.195, 16.608,
        "node/2", 60, "Albert");
    when(overpass.nearby(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(new OsmStoreCandidateResult(OsmLookupStatus.OK, List.of(lidl, albert), "© OpenStreetMap"));

    OsmStoreCandidateResult result = service.atAddress("ALBERT Česká republika, s.r.o.", " Hlavní 1 ", "Brno",
        "602 00", null, 0.3, 1L);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.OK);
    assertThat(result.candidates()).containsExactly(albert, lidl);
    // Bod se zaokrouhlí stejně jako poloha u nearbyOsmStores (sdílená cache Overpassu).
    verify(overpass).nearby(49.195, 16.608, 0.3, OsmStoreLookupService.ROUNDING_SLACK_M);
  }

  @Test
  void addressWithoutCityIsNotGeocoded() {
    OsmStoreCandidateResult result = service.atAddress("Albert", "Hlavní 1", " ", null, null, 0.3, 1L);

    assertThat(result.status()).isEqualTo(OsmLookupStatus.OK);
    assertThat(result.candidates()).isEmpty();
    verify(geocoding, org.mockito.Mockito.never()).lookup(any(), any(), any(), any());
  }
}
