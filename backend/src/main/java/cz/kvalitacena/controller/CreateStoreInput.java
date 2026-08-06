package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.GeoSource;

import java.math.BigDecimal;

public record CreateStoreInput(
    String name,
    Long chainId,
    String street,
    String city,
    String postalCode,
    String country,
    String ico,
    BigDecimal lat,
    BigDecimal lon,
    GeoSource geoSource,
    String osmRef) {
}
