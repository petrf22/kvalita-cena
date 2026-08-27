package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.OffProduct;

/** UNAVAILABLE nikdy nenese produkt; FOUND nese poslední známý platný snapshot. */
public record OffLookupResult(OffLookupStatus status, OffProduct product) {

  public static OffLookupResult found(OffProduct product) {
    return new OffLookupResult(OffLookupStatus.FOUND, product);
  }

  public static OffLookupResult notFound(OffProduct product) {
    return new OffLookupResult(OffLookupStatus.NOT_FOUND, product);
  }

  public static OffLookupResult unavailable() {
    return new OffLookupResult(OffLookupStatus.UNAVAILABLE, null);
  }
}
