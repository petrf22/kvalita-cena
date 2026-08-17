package cz.kvalitacena.controller;

/**
 * Kdy se vlastní záznam propaguje globálně — viz {@code enum PublicationState} ve
 * schema.graphqls a {@link cz.kvalitacena.service.MyContributionsService}.
 */
public enum PublicationState {
  PUBLIC,
  AWAITING_CONFIRMATIONS,
  HIDDEN_AFTER_FLAGS,
  PENDING_MERGE
}
