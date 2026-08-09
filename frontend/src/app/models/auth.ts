import type { MeQuery } from './generated/graphql';

// REST DTO (auth přes /api/auth/*, ne GraphQL) — schema.graphqls o nich nic neví.
export interface OtpRequestResponse {
  challengeUid: string;
  expiresInSec: number;
  resendAfterSec: number;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string | null;
  newUser: boolean;
}

/** Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md). */
export type Viewer = NonNullable<MeQuery['me']>;
