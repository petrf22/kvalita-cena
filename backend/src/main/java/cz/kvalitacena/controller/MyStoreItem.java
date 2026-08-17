package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Store;

import java.time.OffsetDateTime;

public record MyStoreItem(Store store, OffsetDateTime createdAt, PublicationStatus publication) {
}
