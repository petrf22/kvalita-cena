package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;

/**
 * Dvojice podezřele podobných bezkódových položek v jednom obchodním rozsahu. Který z nich je
 * "ten správný", appka nerozhoduje — moderátor si směr sloučení volí sám, proto left/right, ne
 * source/target.
 */
public record DuplicateCandidate(Product left, Product right, double score) {
}
