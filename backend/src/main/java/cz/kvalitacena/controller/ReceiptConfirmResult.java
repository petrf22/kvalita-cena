package cz.kvalitacena.controller;

import java.util.List;

/** Výsledek potvrzení účtenky — souhrn plus co se stalo s každým řádkem. */
public record ReceiptConfirmResult(
    Long receiptId,
    int writtenCount,
    int skippedCount,
    int failedCount,
    List<ReceiptLineResult> lines) {
}
