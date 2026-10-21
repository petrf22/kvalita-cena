package cz.kvalitacena.controller.worker;

import java.util.List;

/** Účtenka se souhlasem s archivací, jejíž obrázky si worker má stáhnout před smazáním. */
public record WorkerArchiveCandidate(Long receiptId, List<String> imagePaths) {
}
