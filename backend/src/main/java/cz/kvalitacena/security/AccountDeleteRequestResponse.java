package cz.kvalitacena.security;

import java.util.UUID;

/** Stejný tvar jako {@link EmailChangeRequestResponse} — kód jde na účtem už vlastněnou adresu. */
public record AccountDeleteRequestResponse(UUID challengeUid, long expiresInSec, long resendAfterSec) {
}
