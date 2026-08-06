package cz.kvalitacena.controller;

import java.time.OffsetDateTime;

/** Veřejná identita přihlášeného uživatele — bez e-mailu a bez DB id (docs/soukromi.md). */
public record Viewer(String publicHandle, String displayName, OffsetDateTime createdAt) {
}
