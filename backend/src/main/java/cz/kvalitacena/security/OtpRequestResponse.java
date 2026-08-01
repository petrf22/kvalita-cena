package cz.kvalitacena.security;

import java.util.UUID;

public record OtpRequestResponse(UUID challengeUid, long expiresInSec, long resendAfterSec) {
}
