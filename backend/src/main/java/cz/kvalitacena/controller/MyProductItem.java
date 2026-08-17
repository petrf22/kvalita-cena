package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;

import java.time.OffsetDateTime;

public record MyProductItem(Product product, OffsetDateTime createdAt, PublicationStatus publication) {
}
