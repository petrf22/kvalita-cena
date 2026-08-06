/** "před 3 dny" místo syrového ISO data — pro tabulku hledání a detail produktu. */
export function formatRelativeDate(iso: string | null | undefined): string {
  if (!iso) return 'zatím nikdy';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;

  const diffMs = Date.now() - date.getTime();
  const diffMinutes = Math.floor(diffMs / 60_000);
  const diffHours = Math.floor(diffMs / 3_600_000);
  const diffDays = Math.floor(diffMs / 86_400_000);

  if (diffMinutes < 1) return 'právě teď';
  if (diffHours < 1) return `před ${diffMinutes} min`;
  if (diffDays < 1) return `před ${diffHours} h`;
  if (diffDays === 1) return 'včera';
  if (diffDays < 7) return `před ${diffDays} dny`;
  return date.toLocaleDateString('cs-CZ');
}
