package cz.kvalitacena.controller;

/**
 * Adresa dohledaná ze souřadnic přes OpenStreetMap Nominatim {@code /reverse} —
 * {@code GeocodingService.reverseGeocode}. Adresní pole jsou nullable: neúplná adresa se
 * projeví jako prázdná pole, nikdy jako chyba dotazu. Výpadek Nominatimu se od "na tomhle místě
 * adresa není" odlišuje {@code status = UNAVAILABLE} — klient při něm pole nesmí přepsat
 * prázdnem (klik do mapy by jinak smazal adresu, kterou uživatel vyplnil nebo převzal z OSM).
 */
public record ReverseGeocodeResult(OsmLookupStatus status, String street, String city, String postalCode,
                                    String country, String osmRef, String attribution) {
}
