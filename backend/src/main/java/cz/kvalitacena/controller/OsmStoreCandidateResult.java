package cz.kvalitacena.controller;

import java.util.List;

/**
 * Obal kolem nabídky z OpenStreetMap — dvě věci, kvůli kterým to není holý seznam:
 *
 * <ul>
 *   <li>{@code attribution} je podle ODbL povinná a UI ji MUSÍ zobrazit; holý seznam by
 *       klientovi dovolil ji prostě neselektovat (stejný důvod jako u {@link GeocodeResult}).
 *   <li>{@code status} odlišuje "v okolí opravdu nic není" od "Overpass je mimo". Bez toho by
 *       uživatel při výpadku založil duplicitu obchodu, který v OSM dávno je. Stejný vzor jako
 *       {@code ProductLookupStatus.OFF_UNAVAILABLE} u skenování zboží.
 * </ul>
 */
public record OsmStoreCandidateResult(
    OsmLookupStatus status,
    List<OsmStoreCandidate> candidates,
    String attribution) {
}
