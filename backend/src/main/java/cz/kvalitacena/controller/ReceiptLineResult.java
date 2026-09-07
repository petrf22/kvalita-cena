package cz.kvalitacena.controller;

/**
 * Co se stalo s jedním řádkem při potvrzení účtenky. Import řádky NESHAZUJE jako dávka:
 * dávková sémantika {@code submitObservations} na účtenku nesedí (docs/rozvoj.md) — stačí,
 * aby jednu z padesáti položek týž člověk týž den zapsal ručně, a celý import by spadl.
 * Duplicitní řádek se proto přeskočí a nahlásí.
 */
public record ReceiptLineResult(
    Long lineId,
    int lineNo,
    String rawLabel,
    /** WRITTEN / SKIPPED_DUPLICATE / SKIPPED_UNMATCHED / SKIPPED_BY_USER / FAILED */
    String outcome,
    Long observationId,
    /** Kód chyby u FAILED, jinak null. */
    String errorCode) {
}
