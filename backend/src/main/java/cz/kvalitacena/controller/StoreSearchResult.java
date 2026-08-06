package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Store;

import java.util.List;

public record StoreSearchResult(List<Store> items, int totalCount, boolean hasMore) {
}
