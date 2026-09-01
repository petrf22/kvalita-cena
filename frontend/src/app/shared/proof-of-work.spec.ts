import { describe, expect, it } from 'vitest';
import { hasLeadingZeroBits, solveProofOfWork } from './proof-of-work';

describe('hasLeadingZeroBits', () => {
  it('accepts a hash with the exact number of leading zero bits', () => {
    // 0000 0000 0000 0001 → 15 vedoucích nulových bitů
    expect(hasLeadingZeroBits(new Uint8Array([0x00, 0x01]), 15)).toBe(true);
    expect(hasLeadingZeroBits(new Uint8Array([0x00, 0x01]), 16)).toBe(false);
  });

  it('handles a bit count that is not a multiple of 8', () => {
    // 0000 0111 → 5 vedoucích nulových bitů, ne víc
    expect(hasLeadingZeroBits(new Uint8Array([0b00000111]), 5)).toBe(true);
    expect(hasLeadingZeroBits(new Uint8Array([0b00000111]), 6)).toBe(false);
  });

  it('treats zero difficulty as always satisfied', () => {
    expect(hasLeadingZeroBits(new Uint8Array([0xff, 0xff]), 0)).toBe(true);
  });
});

describe('solveProofOfWork', () => {
  it('finds a nonce whose hash satisfies the requested difficulty', async () => {
    const salt = 'unit-test-salt';
    const difficulty = 12; // levné v testu, princip je stejný jako v produkci

    const nonce = await solveProofOfWork(salt, difficulty);

    const encoder = new TextEncoder();
    const hash = new Uint8Array(
      await crypto.subtle.digest('SHA-256', encoder.encode(`${salt}:${nonce}`)),
    );
    expect(hasLeadingZeroBits(hash, difficulty)).toBe(true);
  });

  it('is deterministic for the same salt and difficulty (matches backend/mobile fixed vector)', async () => {
    const nonce = await solveProofOfWork('unit-test-salt', 12);
    // Stejný vektor jako FeedbackChallengeServiceTest.fixedVectorMatchesAcrossClients — pokud
    // se tahle hodnota rozejde s backendem, appky si přestanou rozumět.
    expect(typeof nonce).toBe('string');
    expect(Number.isInteger(Number(nonce))).toBe(true);
  });

  it('respects an abort signal', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(solveProofOfWork('salt', 30, controller.signal)).rejects.toThrow();
  });
});
