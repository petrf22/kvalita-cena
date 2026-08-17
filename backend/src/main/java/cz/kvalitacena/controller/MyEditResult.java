package cz.kvalitacena.controller;

import java.util.List;

public record MyEditResult(List<MyEditItem> items, int totalCount, boolean hasMore) {
}
