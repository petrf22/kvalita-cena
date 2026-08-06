package cz.kvalitacena.controller;

/**
 * Odkaz do cizí otevřené databáze. Server data z OFF/OSM NIKDY nestahuje do core.* — posílá jen
 * URL a povinný text zdroje/licence, který UI MUSÍ zobrazit (docs/datovy-model.md, ODbL).
 */
public record ExternalLink(ExternalLinkKind kind, String label, String url, String attribution) {
}
