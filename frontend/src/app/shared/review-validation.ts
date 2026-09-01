/**
 * Čistá klientská validace textu recenze — jen včasná zpětná vazba v UI, ne zdroj pravdy.
 * Backend (`ProductReviewService.saveText`) validuje znovu (stejný limit, `REVIEW_TEXT_EMPTY`/
 * `REVIEW_TEXT_TOO_LONG`), tahle appka jen ušetří uživateli zbytečné odeslání.
 */

/** Shoduje se s `app.review.max-text-length` v backendovém application.yml. */
export const MAX_REVIEW_TEXT_LENGTH = 1000;

export type ReviewValidationErrorCode = 'REVIEW_TEXT_EMPTY' | 'REVIEW_TEXT_TOO_LONG';

/** Kód chyby, nebo null, pokud je text v pořádku k odeslání. Ořezává whitespace stejně jako backend. */
export function reviewTextValidationError(text: string): ReviewValidationErrorCode | null {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return 'REVIEW_TEXT_EMPTY';
  }
  if (trimmed.length > MAX_REVIEW_TEXT_LENGTH) {
    return 'REVIEW_TEXT_TOO_LONG';
  }
  return null;
}

export function remainingReviewCharacters(text: string): number {
  return MAX_REVIEW_TEXT_LENGTH - text.trim().length;
}
