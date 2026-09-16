package cz.kvalitacena.controller;

/** Jednotlivý nález určený k potvrzení uživatelem, nikoli hromadný import katalogu. */
public record OsmStoreCandidate(String name, String street, String city, String postalCode,
                                String country, double lat, double lon, String osmRef, String displayName) {
}
