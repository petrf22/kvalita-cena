package cz.kvalitacena.security;

/**
 * {@code refreshToken} je null pro webového klienta — tam jde jen jako httpOnly cookie,
 * nikdy do těla odpovědi ani do JS paměti (viz docs/soukromi.md).
 *
 * {@code expiresInSec} je životnost PŘILOŽENÉHO access tokenu ({@code app.jwt.access-token-ttl}),
 * ne refresh tokenu. Klient bez ní nemá jak poznat, že mu token dosloužil: prošlý token server
 * tiše zahodí a request doběhne jako anonymní ({@code JwtAuthenticationFilter}), takže dotaz
 * s anonymní variantou (typicky {@code me}) vrátí normální odpověď bez chyby a "počkat si na
 * chybu" jako signál k obnově nefunguje. Klient proto obnovuje preventivně podle tohohle čísla.
 * Vlastní JWT rozebírat nemusí — jeho vnitřek je pro něj neprůhledný.
 */
public record TokenResponse(String accessToken, String refreshToken, boolean newUser, long expiresInSec) {
}
