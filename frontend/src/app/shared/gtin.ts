/**
 * Normalizace čárového kódu na klientovi — jen číslice, bez vedoucích nul. Server normalizuje
 * na GTIN-14 (`backend/.../GtinNormalization.java`), appka tuhle logiku neduplikuje, jen
 * sjednocuje zápisy STEJNÉHO kódu pro cache klíč (`ProductService.lookupByCode`) a pro
 * porovnání zadaného kódu s nabídnutým OFF kandidátem (`product-form-validation.ts`).
 */
export function normalizeCode(code: string): string {
  const digits = code.replace(/\D/g, '');
  return digits.replace(/^0+(?=\d)/, '');
}

/** Délky GTIN podle GS1: GTIN-8 (EAN-8), GTIN-12 (UPC-A), GTIN-13 (EAN-13), GTIN-14. */
const GTIN_LENGTHS: ReadonlySet<number> = new Set([8, 12, 13, 14]);

/**
 * Kód má tvar GTIN a sedí mu kontrolní číslice GS1: číslice zleva od kontrolní se násobí
 * střídavě 3 a 1 (zprava, první váha 3) a kontrolní číslice je `(10 - součet % 10) % 10`.
 *
 * Jen tvar — neříká, jestli je kód GLOBÁLNÍ identifikátor zboží. Etiketa váhového zboží
 * z pultu (2…) projde taky; na rozhodnutí, jestli kód smí do katalogu jako EAN, je
 * `isGlobalGtin`. Na rozdíl od `normalizeCode` nic nečistí: mezera nebo pomlčka uvnitř
 * kódu znamená, že to čárový kód není.
 */
export function isValidGtin(code: string): boolean {
  const digits = code.trim();
  if (!/^\d+$/.test(digits) || !GTIN_LENGTHS.has(digits.length)) return false;
  let sum = 0;
  let weight = 3;
  for (let i = digits.length - 2; i >= 0; i--) {
    sum += Number(digits[i]) * weight;
    weight = 4 - weight;
  }
  return (10 - (sum % 10)) % 10 === Number(digits[digits.length - 1]);
}

/**
 * Prefixy GS1 s omezeným oběhem v 13místném tvaru: 20–29 (vnitroobchodní kódy, typicky
 * etiketa váhového zboží s cenou nebo hmotností uvnitř), 02 a 04 (totéž z UPC-A, číselná
 * soustava 2 a 4) a 05, 98/99 (kupóny, poukázky). Žádný z nich neoznačuje zboží mimo obchod,
 * který ho vytiskl.
 */
const RESTRICTED_GTIN13_PREFIXES = ['2', '02', '04', '05', '98', '99'];

/** U EAN-8 je omezený oběh na první číslici 0 (velocity kódy) a 2 (vnitroobchodní). */
const RESTRICTED_GTIN8_PREFIXES = ['0', '2'];

/**
 * Kód je platný GTIN a zároveň globální identifikátor zboží — jen takový se smí použít jako
 * čárový kód: předvyplnit do formuláře zboží a ukázat u řádku účtenky jako „EAN". Kód
 * s omezeným oběhem platí jen v obchodě, který ho vytiskl (u váhového zboží se navíc mění
 * s každým balením), takže do katalogu jako EAN nepatří — je to vnitroobchodní kód
 * (`code_type = STORE_INTERNAL` s povinným řetězcem, CLAUDE.md).
 *
 * Stejné pravidlo drží server v `GtinNormalization` (backend), klient ho neodvozuje jinak.
 * Prefix se čte z 13místného tvaru: kratší kód se doplní nulami zleva, u GTIN-14 se vezme
 * posledních 13 číslic — prefix GS1 stojí hned za indikátorem balení.
 */
export function isGlobalGtin(code: string): boolean {
  if (!isValidGtin(code)) return false;
  const digits = code.trim();
  if (digits.length === 8) {
    return !RESTRICTED_GTIN8_PREFIXES.some((prefix) => digits.startsWith(prefix));
  }
  const gtin13 = digits.length === 14 ? digits.slice(1) : digits.padStart(13, '0');
  return !RESTRICTED_GTIN13_PREFIXES.some((prefix) => gtin13.startsWith(prefix));
}
