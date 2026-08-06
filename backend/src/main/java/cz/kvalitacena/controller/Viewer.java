package cz.kvalitacena.controller;

import java.time.OffsetDateTime;

/**
 * Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md).
 * {@code trusted} = práh důvěry (docs/reputace.md, etapa-1 aproximace T2) — klient tím umí
 * vysvětlit, proč nový obchod/zboží zatím nikdo jiný nevidí.
 */
public record Viewer(String publicHandle, String displayName, OffsetDateTime createdAt, boolean trusted) {
}
