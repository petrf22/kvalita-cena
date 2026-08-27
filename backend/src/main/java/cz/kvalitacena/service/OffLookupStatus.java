package cz.kvalitacena.service;

/** Výsledek lookupu v lokálním snapshotu a případně v Open Food Facts API. */
public enum OffLookupStatus {
  FOUND,
  NOT_FOUND,
  UNAVAILABLE
}
