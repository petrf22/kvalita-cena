package cz.kvalitacena.security;

/**
 * {@code refreshToken} je null pro webového klienta — tam jde jen jako httpOnly cookie,
 * nikdy do těla odpovědi ani do JS paměti (viz docs/soukromi.md).
 */
public record TokenResponse(String accessToken, String refreshToken, boolean newUser) {
}
