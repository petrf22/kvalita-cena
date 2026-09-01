package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.Store;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Jeden nahlášený záznam ve frontě k přezkumu (docs/reputace.md, "Moderace") — přesně jedno
 * z {@code product}/{@code store}/{@code photo}/{@code review} je vyplněné, podle
 * {@code recordType} (stejný polymorfní tvar jako {@link MyEditItem}).
 * {@code authorPublicUid}/{@code authorHandle} nikdy neprozradí {@code record_flag.user_id}
 * (kdo nahlásil, docs/soukromi.md) — je to autor CÍLE.
 */
public record FlaggedRecordItem(RecordType recordType, Long recordId, long flagCount,
    OffsetDateTime firstFlaggedAt, OffsetDateTime lastFlaggedAt, List<String> reasons, boolean hidden,
    UUID authorPublicUid, String authorHandle, Product product, Store store, Photo photo, FlaggedReview review) {
}
