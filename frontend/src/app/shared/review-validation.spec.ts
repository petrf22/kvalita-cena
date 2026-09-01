import { describe, expect, it } from 'vitest';
import {
  MAX_REVIEW_TEXT_LENGTH,
  remainingReviewCharacters,
  reviewTextValidationError,
} from './review-validation';

describe('reviewTextValidationError', () => {
  it('accepts a normal review text', () => {
    expect(reviewTextValidationError('Dobré mléko, koupím znovu.')).toBeNull();
  });

  it('rejects an empty text', () => {
    expect(reviewTextValidationError('')).toBe('REVIEW_TEXT_EMPTY');
  });

  it('rejects whitespace-only text', () => {
    expect(reviewTextValidationError('   \n  ')).toBe('REVIEW_TEXT_EMPTY');
  });

  it('accepts text at exactly the length limit', () => {
    expect(reviewTextValidationError('a'.repeat(MAX_REVIEW_TEXT_LENGTH))).toBeNull();
  });

  it('rejects text over the length limit', () => {
    expect(reviewTextValidationError('a'.repeat(MAX_REVIEW_TEXT_LENGTH + 1))).toBe(
      'REVIEW_TEXT_TOO_LONG',
    );
  });

  it('trims whitespace before counting length', () => {
    const padded = `  ${'a'.repeat(MAX_REVIEW_TEXT_LENGTH)}  `;
    expect(reviewTextValidationError(padded)).toBeNull();
  });
});

describe('remainingReviewCharacters', () => {
  it('counts down from the limit', () => {
    expect(remainingReviewCharacters('abc')).toBe(MAX_REVIEW_TEXT_LENGTH - 3);
  });

  it('ignores surrounding whitespace, same as the validation itself', () => {
    expect(remainingReviewCharacters('  abc  ')).toBe(MAX_REVIEW_TEXT_LENGTH - 3);
  });

  it('goes negative once the limit is exceeded, so UI can show the overflow', () => {
    const overLimit = 'a'.repeat(MAX_REVIEW_TEXT_LENGTH + 5);
    expect(remainingReviewCharacters(overLimit)).toBe(-5);
  });
});
