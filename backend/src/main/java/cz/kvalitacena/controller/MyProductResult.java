package cz.kvalitacena.controller;

import java.util.List;

public record MyProductResult(List<MyProductItem> items, int totalCount, boolean hasMore) {
}
