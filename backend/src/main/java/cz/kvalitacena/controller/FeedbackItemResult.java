package cz.kvalitacena.controller;

import java.util.List;

public record FeedbackItemResult(List<FeedbackItem> items, int totalCount, boolean hasMore) {
}
