package cz.kvalitacena.controller.worker;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Úloha předaná workeru — jen to, co ke čtení účtenky potřebuje: obrázky v pořadí a nápověda
 * k datu (kdy uživatel fotil). Žádná identita vlastníka.
 *
 * <p>{@code rawText} je vyplněný jen u nového rozpoznání účtenky, jejíž obrázky už smazala
 * retence: {@code imagePaths} je pak prázdné a worker znovu rozebere uložený text (bez OCR,
 * docs/ai.md, „Nové rozpoznání").
 */
public record WorkerReceiptJob(Long receiptId, OffsetDateTime capturedAt, OffsetDateTime leaseUntil,
    List<String> imagePaths, String rawText) {
}
