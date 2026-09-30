package cz.kvalitacena.controller;

public record ReceiptLineCounts(int total, int unmatched, int suggested, int matched, int ignored) {
}
