package cz.kvalitacena.controller;

import java.util.List;

public record ProductSearchResult(List<ProductSearchItem> items, int totalCount, boolean hasMore) {
}
