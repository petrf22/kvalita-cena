package cz.kvalitacena.controller;

/**
 * Provozovna nalezená v OpenStreetMap přes Overpass — nabídka k založení obchodu, nic z toho
 * se samo nikam neukládá. Teprve když člověk jednoho kandidáta potvrdí ve formuláři, uloží se
 * jeho údaje do {@code core.store} s {@code data_origin = 'OSM_DERIVED'} (docs/datovy-model.md,
 * pravidlo 1 a 2).
 *
 * @param brand tag {@code brand}/{@code operator} — slouží jen k předvyplnění NAŠEPTÁVAČE
 *              řetězců ({@code core.retail_chain}), ne k zakládání nového řetězce
 */
public record OsmStoreCandidate(
    String name,
    String street,
    String city,
    String postalCode,
    String country,
    double lat,
    double lon,
    String osmRef,
    double distanceMeters,
    String brand) {
}
