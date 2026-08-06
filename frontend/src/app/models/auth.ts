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
export interface Viewer {
  publicHandle: string;
  displayName: string | null;
  createdAt: string;
}
