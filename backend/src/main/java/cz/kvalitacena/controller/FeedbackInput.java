package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.FeedbackCategory;

public record FeedbackInput(
    FeedbackCategory category,
    String message,
    String contactEmail,
    String pageRef,
    String appVersion,
    String diagnostics,
    // Proof-of-work (docs/nasazeni.md, obrana proti spamu) — nepovinné jen kvůli starším
    // klientům bez PoW, FeedbackSpamDetector chybějící dvojici sám penalizuje.
    String challenge,
    String nonce,
    // Honeypot — appka ho nikdy nevyplní, jen ho v UI schová (viz FeedbackSpamDetector).
    String website) {
}
