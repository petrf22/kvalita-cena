package cz.kvalitacena.db.entity;

/**
 * Původ dat záznamu kvůli ODbL (docs/datovy-model.md, pravidla 2 a 3) — co není OWN, vypadává
 * z "čistého" exportu vlastních dat.
 *
 * <p>Enum je sdílený, ale <b>každá tabulka z něj smí použít jen svou dvojici</b> a hlídá to
 * CHECK v databázi: {@code core.product} má {@code OWN}/{@code OFF_DERIVED},
 * {@code core.store} má {@code OWN}/{@code OSM_DERIVED}.
 */
public enum DataOrigin {
  /** Vlastní komunitní data. */
  OWN,
  /** Ručně opsáno z Open Food Facts (jen core.product). */
  OFF_DERIVED,
  /** Název a adresa převzaté z potvrzeného kandidáta z OpenStreetMap (jen core.store). */
  OSM_DERIVED
}
