package cz.kvalitacena.controller;

import java.util.List;

public record MyStoreResult(List<MyStoreItem> items, int totalCount, boolean hasMore) {
}
