package cz.kvalitacena.controller;

/** GraphQL projekce značky dovoluje vedle core.brand vrátit i virtuální OFF značku. */
public record ProductBrand(String id, String name, String slug) {
}
