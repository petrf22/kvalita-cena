package cz.kvalitacena.controller;

import java.util.List;

public record MyReviewResult(List<MyReviewItem> items, int totalCount, boolean hasMore) {
}
