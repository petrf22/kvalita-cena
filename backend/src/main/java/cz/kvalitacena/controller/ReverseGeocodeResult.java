package cz.kvalitacena.controller;

/**
 * Adresa dohledaná ze souřadnic přes OpenStreetMap Nominatim {@code /reverse} —
 * {@code GeocodingService.reverseGeocode}. Všechna pole jsou nullable: neúplná adresa
 * (nebo výpadek Nominatimu) se projeví jako prázdná pole, nikdy jako chyba dotazu.
 */
public record ReverseGeocodeResult(String street, String city, String postalCode, String country,
                                    String osmRef, String attribution) {
}
