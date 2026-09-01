/**
 * Proof-of-work řešič pro formulář zpětné vazby (docs/nasazeni.md, obrana proti spamu) —
 * náhrada CAPTCHY, kterou appka nesmí použít (docs/soukromi.md, žádné externí skripty/CDN
 * třetí strany). Definice musí být BIT-PŘESNĚ stejná jako backend
 * (`FeedbackChallengeService`) a mobil (`ProofOfWork.kt`): hledá se nejmenší nezáporné celé
 * `nonce`, pro které má SHA-256(UTF8(salt + ":" + nonce)) `difficulty` vedoucích nulových bitů.
 *
 * Čistá funkce nad Web Crypto, testovatelná bez Workeru — samotné volání v appce jde přes
 * `proof-of-work.worker.ts`, ať se počítání neděje na hlavním vlákně.
 */
export async function solveProofOfWork(
  salt: string,
  difficulty: number,
  signal?: AbortSignal,
): Promise<string> {
  const encoder = new TextEncoder();
  for (let nonce = 0; ; nonce++) {
    if (signal?.aborted) {
      throw new DOMException('Proof-of-work zrušeno', 'AbortError');
    }
    const data = encoder.encode(`${salt}:${nonce}`);
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    if (hasLeadingZeroBits(new Uint8Array(hashBuffer), difficulty)) {
      return String(nonce);
    }
  }
}

export function hasLeadingZeroBits(hash: Uint8Array, bits: number): boolean {
  const fullBytes = Math.floor(bits / 8);
  for (let i = 0; i < fullBytes; i++) {
    if (hash[i] !== 0) return false;
  }
  const remainingBits = bits % 8;
  if (remainingBits === 0) return true;
  const mask = (0xff << (8 - remainingBits)) & 0xff;
  return (hash[fullBytes] & mask) === 0;
}
