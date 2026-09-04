import { readRememberedStore } from './last-store-service';

describe('readRememberedStore', () => {
  it('vrátí obchod mladší než 30 dní', () => {
    const storage = new MapStorage();
    storage.setItem('kvalita-cena.last-price-store', JSON.stringify({ id: '7', savedAt: 1_000 }));
    expect(readRememberedStore(storage, 1_000 + 29 * 24 * 60 * 60 * 1000)).toBe('7');
  });

  it('prošlou nebo poškozenou hodnotu odstraní', () => {
    const storage = new MapStorage();
    storage.setItem('kvalita-cena.last-price-store', JSON.stringify({ id: '7', savedAt: 0 }));
    expect(readRememberedStore(storage, 31 * 24 * 60 * 60 * 1000)).toBeNull();
    expect(storage.getItem('kvalita-cena.last-price-store')).toBeNull();
  });
});

class MapStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length(): number {
    return this.values.size;
  }
  clear(): void {
    this.values.clear();
  }
  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }
  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }
  removeItem(key: string): void {
    this.values.delete(key);
  }
  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}
