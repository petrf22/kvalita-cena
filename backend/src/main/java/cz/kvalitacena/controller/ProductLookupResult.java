package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;

public record ProductLookupResult(ProductLookupStatus status, Product product, ExternalProductCandidate candidate) {
}
