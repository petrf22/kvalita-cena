package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.Store;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Vlastní úprava cizího záznamu (core.product_user_edit / core.store_user_edit) — přesně
 * jeden z {@code product}/{@code store} je vždy vyplněný, podle {@code recordType}. Stav
 * zveřejnění je vždy {@code PENDING_MERGE} (konsolidační job zatím neběží).
 */
public record MyEditItem(RecordType recordType, Product product, Store store, OffsetDateTime updatedAt,
                           List<String> changedFields, PublicationStatus publication) {
}
