/**
 * „Nakoupeno s kartou obchodu" u účtenky (docs/soukromi.md, „Účtenky"). Na server jde jen
 * ano/ne k jedné účtence (`setReceiptLoyaltyCard`) — kterou kartu kdo má, appka serveru
 * neříká. Poslední volba pro řetězec (u samostatné prodejny pro obchod) se pamatuje jen
 * v tomhle prohlížeči a předvyplní se u další účtenky odtamtud.
 */

const STORAGE_PREFIX = 'kac.loyaltyCard.';

interface StoreRef {
  id: string;
  chain?: { id: string } | null;
}

/** Klíč zapamatované volby — karta patří řetězci, samostatná prodejna má vlastní. */
export function loyaltyCardKey(store: StoreRef | null | undefined): string | null {
  if (!store) return null;
  return store.chain ? `chain:${store.chain.id}` : `store:${store.id}`;
}

/** Zaškrtnutí má smysl jen u účtenky, kde je aspoň jedna sleva — jinak nic nemění. */
export function hasDiscountLine(lines: readonly { discountAmount?: number | null }[]): boolean {
  return lines.some((line) => line.discountAmount != null && line.discountAmount > 0);
}

export function rememberedLoyaltyCard(key: string | null): boolean | null {
  if (!key) return null;
  try {
    const value = localStorage.getItem(STORAGE_PREFIX + key);
    return value === 'true' ? true : value === 'false' ? false : null;
  } catch {
    return null; // soukromé okno, zablokované úložiště — bez předvyplnění
  }
}

export function rememberLoyaltyCard(key: string | null, used: boolean): void {
  if (!key) return;
  try {
    localStorage.setItem(STORAGE_PREFIX + key, String(used));
  } catch {
    // Předvyplnění je jen pohodlí — volba na účtence je uložená na serveru i bez něj.
  }
}
