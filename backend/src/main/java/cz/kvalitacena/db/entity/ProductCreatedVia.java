package cz.kvalitacena.db.entity;

/**
 * Jak zboží vzniklo (core.product.created_via). {@link #RECEIPT} je „zboží z účtenky" —
 * druhová položka založená zveřejněním nespárovaného řádku účtenky, bez ověřených náležitostí
 * (docs/datovy-model.md, „Účtenky"). Klient ho kreslí jinak a hledání ho řadí za ostatní shody.
 */
public enum ProductCreatedVia {
  USER,
  RECEIPT
}
