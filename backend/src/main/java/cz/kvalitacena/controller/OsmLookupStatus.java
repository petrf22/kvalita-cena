package cz.kvalitacena.controller;

/** Proč je nabídka z OpenStreetMap prázdná — viz {@link OsmStoreCandidateResult}. */
public enum OsmLookupStatus {
  /** Dotaz proběhl. Prázdné {@code candidates} znamenají, že v okolí opravdu nic není. */
  OK,
  /** Overpass je nedostupný, pomalý, nebo vypnutý konfigurací — o okolí nevíme nic. */
  UNAVAILABLE
}
