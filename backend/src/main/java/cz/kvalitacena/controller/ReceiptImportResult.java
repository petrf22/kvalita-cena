package cz.kvalitacena.controller;

/**
 * Odpověď na nahrání účtenky. {@code alreadyImported} znamená, že tentýž dokument už na serveru
 * je — import je idempotentní podle otisku, opakované nahrání nezaloží druhou kopii.
 */
public record ReceiptImportResult(
    Long receiptId,
    boolean alreadyImported,
    boolean totalMatches,
    int lineCount,
    int matchedCount,
    int suggestedCount,
    int unmatchedCount) {
}
