package cz.kvalitacena.security;

/** Jen pro Android — web posílá refresh token výhradně jako httpOnly cookie. */
public record RefreshRequest(String refreshToken) {
}
