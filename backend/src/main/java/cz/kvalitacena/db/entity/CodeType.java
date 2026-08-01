package cz.kvalitacena.db.entity;

/** STORE_INTERNAL má vždy povinný chain_id — nikdy globální identifikátor napříč řetězci. */
public enum CodeType {
  GTIN,
  PLU,
  STORE_INTERNAL
}
