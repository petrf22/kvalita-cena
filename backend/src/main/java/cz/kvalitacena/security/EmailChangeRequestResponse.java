package cz.kvalitacena.security;

import java.util.UUID;

/** Stejný tvar bez ohledu na to, jestli je nová adresa volná, nebo už patří jinému účtu. */
public record EmailChangeRequestResponse(UUID challengeUid, long expiresInSec, long resendAfterSec) {
}
