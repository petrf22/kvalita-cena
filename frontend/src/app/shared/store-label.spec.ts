import { describe, expect, it } from 'vitest';
import { storeLabel } from './store-label';

describe('storeLabel', () => {
  it('hides the country code when it matches the viewer home country', () => {
    const store = { name: 'Lidl', city: 'Brno', country: 'CZ' };
    expect(storeLabel(store, 'CZ')).toBe('Lidl — Brno');
  });

  it('appends the country code when it differs from the viewer home country', () => {
    const store = { name: 'Lidl', city: 'Bratislava', country: 'SK' };
    expect(storeLabel(store, 'CZ')).toBe('Lidl — Bratislava (SK)');
  });

  it('shows the country when the viewer has no home country set', () => {
    const store = { name: 'Lidl', city: 'Bratislava', country: 'SK' };
    expect(storeLabel(store, null)).toBe('Lidl — Bratislava (SK)');
  });
});
