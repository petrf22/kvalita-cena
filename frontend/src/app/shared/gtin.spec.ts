import { describe, expect, it } from 'vitest';
import { normalizeCode } from './gtin';

describe('normalizeCode', () => {
  it('strips leading zeros', () => {
    expect(normalizeCode('08595000000010')).toBe('8595000000010');
  });

  it('strips non-digit characters', () => {
    expect(normalizeCode('859-500-000')).toBe('859500000');
  });

  it('keeps a single zero as zero', () => {
    expect(normalizeCode('0')).toBe('0');
    expect(normalizeCode('000')).toBe('0');
  });

  it('leaves an already-normalized code unchanged', () => {
    expect(normalizeCode('3017620422003')).toBe('3017620422003');
  });
});
