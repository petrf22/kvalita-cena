package cz.kvalitacena.service;

import cz.kvalitacena.controller.OsmStoreCandidate;
import cz.kvalitacena.db.entity.Store;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Vyhodí z nabídky z OpenStreetMap ty provozovny, které appka ve vlastním katalogu už zná —
 * jinak by uživatel založil duplicitu a narazil až na {@code DUPLICATE_STORE} z
 * {@code uq_store_identity}, tedy přesně na tu slepou uličku, kterou má nabídka odstranit.
 *
 * <p>Dvě shody stačí k vyřazení: stejný {@code osm_ref} (obchod z OSM už někdo založil), nebo
 * stejná normalizovaná identita — country + název + město + ulice, tedy přesně klíč
 * {@code uq_store_identity} (docs/datovy-model.md, "Identita provozovny"). Normalizace je tady
 * ZJEDNODUŠENÁ obdoba {@code core.norm_text}: malá písmena, bez diakritiky, sjednocené mezery.
 * Nemusí s SQL funkcí souhlasit do posledního znaku — je to filtr nabídky, tvrdou pojistkou
 * zůstává unikátní index v databázi.
 *
 * <p>Statická čistá funkce bez Springu, ať jde otestovat JUnitem bez kontextu (stejný důvod
 * jako u {@code PriceChartGeometry} na mobilu).
 */
public final class OsmStoreCandidateFilter {

  private OsmStoreCandidateFilter() {
  }

  public static List<OsmStoreCandidate> withoutKnownStores(List<OsmStoreCandidate> candidates,
      List<Store> knownStores, String fallbackCountry) {
    if (candidates.isEmpty() || knownStores.isEmpty()) {
      return candidates;
    }
    Set<String> knownOsmRefs = new HashSet<>();
    Set<String> knownIdentities = new HashSet<>();
    for (Store store : knownStores) {
      if (store.getOsmRef() != null && !store.getOsmRef().isBlank()) {
        knownOsmRefs.add(store.getOsmRef());
      }
      knownIdentities.add(identity(store.getCountry(), store.getName(), store.getCity(), store.getStreet()));
    }
    return candidates.stream()
        .filter(candidate -> !knownOsmRefs.contains(candidate.osmRef()))
        .filter(candidate -> !knownIdentities.contains(identity(
            candidate.country() != null ? candidate.country() : fallbackCountry,
            candidate.name(), candidate.city(), candidate.street())))
        .toList();
  }

  private static String identity(String country, String name, String city, String street) {
    return String.join("|", norm(country), norm(name), norm(city), norm(street));
  }

  /** Zjednodušená obdoba {@code core.norm_text} — viz javadoc třídy, proč nemusí sedět přesně. */
  private static String norm(String value) {
    if (value == null) {
      return "";
    }
    String withoutDiacritics = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    return withoutDiacritics.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }
}
