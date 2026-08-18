package cz.kvalitacena.controller;

import java.util.List;

public record ModerationObservationResult(List<ModerationObservationItem> items, int totalCount, boolean hasMore) {
}
