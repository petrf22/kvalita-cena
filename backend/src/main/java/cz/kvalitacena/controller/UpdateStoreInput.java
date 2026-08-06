package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.GeoSource;

import java.math.BigDecimal;

/**
 * Patch nad core.store (core.store_user_edit) — {@code null} u pole znamená "nezměněno".
 * {@code clearChain}/{@code clearStreet}/{@code clearPostalCode}/{@code clearIco} řeší
 * volitelné hodnoty, které jde smazat (např. chybně zadané IČO) — na rozdíl od name/city, ty
 * se jen mění, nikdy nemažou.
 */
public record UpdateStoreInput(
    String name,
    Long chainId,
    Boolean clearChain,
    String street,
    Boolean clearStreet,
    String city,
    String postalCode,
    Boolean clearPostalCode,
    String country,
    String ico,
    Boolean clearIco,
    BigDecimal lat,
    BigDecimal lon,
    GeoSource geoSource,
    String osmRef) {
}
