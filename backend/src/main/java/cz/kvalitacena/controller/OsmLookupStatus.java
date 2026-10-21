package cz.kvalitacena.controller;

/**
 * Proč je odpověď z OpenStreetMap prázdná — nabídka provozoven ({@link OsmStoreCandidateResult})
 * i zpětné geokódování ({@link ReverseGeocodeResult}).
 */
public enum OsmLookupStatus {
  /** Dotaz proběhl. Prázdné {@code candidates} znamenají, že v okolí opravdu nic není. */
  OK,
  /** Služba (Overpass/Nominatim) je nedostupná, pomalá, nebo vypnutá konfigurací — nevíme nic. */
  UNAVAILABLE
}
