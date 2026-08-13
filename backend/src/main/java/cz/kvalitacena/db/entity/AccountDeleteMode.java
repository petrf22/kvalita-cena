package cz.kvalitacena.db.entity;

/**
 * Dva režimy výmazu účtu (docs/soukromi.md, "GDPR") — {@code ANONYMIZE} nechává vlastní cenové
 * záznamy v datech jako anonymizovanou statistiku ve veřejném zájmu (spoléhá na
 * {@code fk_price_observation_submitter ON DELETE SET NULL}, stejně jako denní pseudonymizace
 * po 180 dnech), {@code DELETE_CONTENT} navíc observace uživatele skutečně smaže a vynutí
 * přepočet dotčených buněk agregátu.
 */
public enum AccountDeleteMode {
  ANONYMIZE,
  DELETE_CONTENT
}
