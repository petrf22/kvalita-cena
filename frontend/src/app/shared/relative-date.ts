const UNITS: readonly [Intl.RelativeTimeFormatUnit, number][] = [
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
];

/**
 * "před 3 dny"/"3 days ago"/"przed 3 dniami" — plurály i zvláštní případy ("včera") řeší
 * platformní `Intl.RelativeTimeFormat`, ne překladový soubor (docs/lokalizace.md); ICU plurály
 * ve čtyřech jazycích ručně psané by tohle jen duplikovaly a hůř. Vrací `null` pro chybějící
 * datum — volající (RelativeDatePipe) si dosadí lokalizované "zatím nikdy".
 */
export function formatRelativeDate(iso: string | null | undefined, tag: string): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;

  const diffMs = date.getTime() - Date.now();
  if (Math.abs(diffMs) >= 7 * 86_400_000) {
    return new Intl.DateTimeFormat(tag, { dateStyle: 'medium' }).format(date);
  }
  const rtf = new Intl.RelativeTimeFormat(tag, { numeric: 'auto' });
  for (const [unit, unitMs] of UNITS) {
    const value = Math.round(diffMs / unitMs);
    if (value !== 0) return rtf.format(value, unit);
  }
  return rtf.format(0, 'minute'); // "právě teď"
}
