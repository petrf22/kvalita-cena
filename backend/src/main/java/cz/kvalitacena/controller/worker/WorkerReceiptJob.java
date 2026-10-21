package cz.kvalitacena.controller.worker;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Úloha předaná workeru — jen to, co ke čtení účtenky potřebuje: obrázky v pořadí a nápověda
 * k datu (kdy uživatel fotil). Žádná identita vlastníka.
 */
public record WorkerReceiptJob(Long receiptId, OffsetDateTime capturedAt, OffsetDateTime leaseUntil,
    List<String> imagePaths) {
}
