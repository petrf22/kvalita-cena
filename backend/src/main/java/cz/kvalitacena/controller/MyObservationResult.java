package cz.kvalitacena.controller;

import java.util.List;

public record MyObservationResult(List<MyObservationItem> items, int totalCount, boolean hasMore) {
}
