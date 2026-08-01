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
