package cz.kvalitacena.controller;

import java.util.List;

public record FlaggedRecordResult(List<FlaggedRecordItem> items, int totalCount, boolean hasMore) {
}
