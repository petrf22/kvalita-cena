package cz.kvalitacena.db.entity;

public enum StoreStatus {
  ACTIVE,
  CLOSED,
  PENDING,
  // Duplicitní provozovna sloučená do jiné (Store.mergedInto) — vyjmuta z uq_store_identity.
  MERGED
}
