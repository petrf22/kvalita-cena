package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.FeedbackCategory;

public record FeedbackInput(
    FeedbackCategory category,
    String message,
    String contactEmail,
    String pageRef,
    String appVersion,
    String diagnostics) {
}
