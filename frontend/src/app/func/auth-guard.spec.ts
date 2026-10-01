import { describe, expect, it } from 'vitest';
import { safeReturnUrl } from './auth-guard';

describe('safeReturnUrl', () => {
  it('pustí jen cestu uvnitř appky', () => {
    expect(safeReturnUrl('/receipts/2')).toBe('/receipts/2');
    expect(safeReturnUrl('/receipts?page=2')).toBe('/receipts?page=2');
  });

  it('zahodí adresu mimo appku (open redirect)', () => {
    expect(safeReturnUrl('https://evil.example')).toBeNull();
    expect(safeReturnUrl('//evil.example')).toBeNull();
    expect(safeReturnUrl('/\\evil.example')).toBeNull();
    expect(safeReturnUrl('')).toBeNull();
    expect(safeReturnUrl(null)).toBeNull();
  });
});
