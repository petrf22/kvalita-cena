package cz.kvalitacena.controller;

/**
 * Fotka zboží nebo provozovny (core.media) v GraphQL — {@code url}/{@code thumbnailUrl} vedou
 * na {@code MediaController} (REST, mimo GraphQL, protože multipart upload/binární stahování
 * Spring for GraphQL nepodporuje). {@code attribution} je povinný text licence, klient ho MUSÍ
 * zobrazit u galerie (stejné pravidlo jako u {@link ExternalLink} a {@link GeocodeResult}).
 */
public record Photo(Long id, String url, String thumbnailUrl, int width, int height, String caption,
                     boolean mine, boolean hidden, String attribution) {
}
