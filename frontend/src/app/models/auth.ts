import type { MeQuery } from './generated/graphql';

export { ProfileVisibility, ProfileField, Audience } from './generated/enums';

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

/**
 * REST DTO pro změnu přihlašovacího e-mailu (/api/auth/email/change/*, ne GraphQL) — vlastní
 * tok vedle OTP loginu (docs/soukromi.md, "Profil uživatele a viditelnost").
 */
export type EmailChangeRequestResponse = OtpRequestResponse;

/** Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md). */
export type Viewer = NonNullable<MeQuery['me']>;

/** Profil — vždy plný pohled vlastníka, nikdy filtrovaný podle visibility (docs/soukromi.md). */
export type Profile = Viewer['profile'];

export type ProfileFieldAudience = Profile['visibleFields'][number];
