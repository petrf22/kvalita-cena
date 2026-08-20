package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.FeedbackCategory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jedno hlášení ve frontě zpětné vazby (core.feedback, jen moderátor) — na rozdíl od
 * {@link FlaggedRecordItem} SE {@code authorPublicUid}/{@code authorHandle} vrací u autora
 * i {@code contactEmail}, protože bez nich není komu odpovědět (docs/soukromi.md, vědomá
 * odchylka od nahlašování). {@code authorPublicUid} je {@code null} u anonymního odeslání.
 */
public record FeedbackItem(long id, FeedbackCategory category, String message, String contactEmail,
    ClientKind clientKind, String appVersion, String platformInfo, String locale, String country,
    String pageRef, String diagnostics, OffsetDateTime createdAt, boolean handled, String handledNote,
    UUID authorPublicUid, String authorHandle) {
}
