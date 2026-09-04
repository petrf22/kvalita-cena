import { Injectable } from '@angular/core';

const STORAGE_KEY = 'kvalita-cena.last-price-store';
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

interface StoredStore {
  id: string;
  savedAt: number;
}

export function readRememberedStore(storage: Storage, now = Date.now()): string | null {
  const raw = storage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<StoredStore>;
    if (typeof value.id !== 'string' || typeof value.savedAt !== 'number') throw new Error();
    if (now - value.savedAt > MAX_AGE_MS) {
      storage.removeItem(STORAGE_KEY);
      return null;
    }
    return value.id;
  } catch {
    storage.removeItem(STORAGE_KEY);
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class LastStoreService {
  read(): string | null {
    return readRememberedStore(localStorage);
  }

  remember(id: string): void {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ id, savedAt: Date.now() } satisfies StoredStore),
    );
  }

  clear(): void {
    localStorage.removeItem(STORAGE_KEY);
  }
}
