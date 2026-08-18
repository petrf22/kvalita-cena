package cz.kvalitacena.controller;

import java.time.OffsetDateTime;

/**
 * Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md).
 * {@code trusted} = práh důvěry (docs/reputace.md, etapa-1 aproximace T2) — klient tím umí
 * vysvětlit, proč nový obchod/zboží zatím nikdo jiný nevidí. {@code locale}/{@code country}
 * jsou uložená preference pro ASYNCHRONNÍ výstup (OTP e-mail) — {@code null}, dokud si ji
 * uživatel nenastaví přes {@code setLocale} (docs/lokalizace.md); synchronní odpovědi API se
 * řídí Accept-Language, ne tímhle polem. {@code profile} je vždy PLNÝ pohled vlastníka na
 * vlastní profil (docs/soukromi.md, "Profil uživatele a viditelnost") — {@code Viewer} je vždy
 * "já", takže se nikdy nefiltruje podle {@code visibility}.
 */
public record Viewer(String publicHandle, String displayName, OffsetDateTime createdAt, boolean trusted,
    boolean moderator, String locale, String country, Profile profile) {
}
