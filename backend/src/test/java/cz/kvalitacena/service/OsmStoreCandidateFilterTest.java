package cz.kvalitacena.service;

import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.db.entity.Store;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nabídka z OSM nesmí obsahovat obchod, který appka ve vlastním katalogu už zná — jinak
 * uživatel narazí na DUPLICATE_STORE z uq_store_identity, tedy přesně na tu slepou uličku,
 * kterou má nabídka odstranit.
 */
class OsmStoreCandidateFilterTest {

  private OsmStoreCandidate candidate(String name, String street, String city, String country, String osmRef) {
    return new OsmStoreCandidate(name, street, city, "60200", country, 49.2, 16.6, osmRef, 120, null);
  }

  private Store store(String name, String street, String city, String country, String osmRef) {
    return Store.builder().name(name).street(street).city(city).country(country).osmRef(osmRef).build();
  }

  @Test
  void dropsCandidateWithKnownOsmRef() {
    List<OsmStoreCandidate> result = OsmStoreCandidateFilter.withoutKnownStores(
        List.of(candidate("Albert", "Joštova 4", "Brno", "CZ", "way/111")),
        List.of(store("Uplne jiny nazev", "Jina 1", "Brno", "CZ", "way/111")),
        "CZ");

    assertThat(result).isEmpty();
  }

  @Test
  void dropsCandidateMatchingNormalizedIdentity() {
    // Obchod někdo založil ručně, osm_ref tedy nemá — shodu musí najít normalizovaná identita
    // (country + název + město + ulice), stejný klíč jako uq_store_identity.
    List<OsmStoreCandidate> result = OsmStoreCandidateFilter.withoutKnownStores(
        List.of(candidate("Žabka", "Nádražní 38", "Brno", "CZ", "node/222")),
        List.of(store("ŽABKA", "nádražní  38", "brno", "CZ", null)),
        "CZ");

    assertThat(result).isEmpty();
  }

  @Test
  void keepsCandidateFromDifferentCountry() {
    List<OsmStoreCandidate> result = OsmStoreCandidateFilter.withoutKnownStores(
        List.of(candidate("Lidl", "Hlavná 1", "Skalica", "SK", "way/333")),
        List.of(store("Lidl", "Hlavná 1", "Skalica", "CZ", null)),
        "CZ");

    assertThat(result).hasSize(1);
  }

  @Test
  void candidateWithoutCountryFallsBackToViewerCountry() {
    // addr:country v OSM často chybí — bez fallbacku by shoda s vlastním obchodem unikla.
    List<OsmStoreCandidate> result = OsmStoreCandidateFilter.withoutKnownStores(
        List.of(candidate("Lidl", null, "Brno", null, "way/444")),
        List.of(store("Lidl", null, "Brno", "CZ", null)),
        "CZ");

    assertThat(result).isEmpty();
  }

  @Test
  void keepsUnknownCandidate() {
    List<OsmStoreCandidate> result = OsmStoreCandidateFilter.withoutKnownStores(
        List.of(candidate("Farma Sedlák", "Vinohrady 12", "Brno", "CZ", "node/555")),
        List.of(store("Albert", "Joštova 4", "Brno", "CZ", "way/111")),
        "CZ");

    assertThat(result).hasSize(1);
  }
}
