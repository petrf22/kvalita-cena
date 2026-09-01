/// <reference lib="webworker" />
import { solveProofOfWork } from './proof-of-work';

/** Tenký obal nad {@link solveProofOfWork} — počítání běží mimo hlavní vlákno, ať appka
 *  nezamrzne formulář zpětné vazby na stovky ms (docs/nasazeni.md, obrana proti spamu). */
addEventListener(
  'message',
  async ({ data }: MessageEvent<{ salt: string; difficulty: number }>) => {
    const nonce = await solveProofOfWork(data.salt, data.difficulty);
    postMessage({ nonce });
  },
);
