package cz.kvalitacena.controller;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Fotka zboží nebo provozovny (core.media) v GraphQL — {@code url}/{@code thumbnailUrl} vedou
 * na {@code MediaController} (REST, mimo GraphQL, protože multipart upload/binární stahování
 * Spring for GraphQL nepodporuje). {@code attribution} je povinný text licence, klient ho MUSÍ
 * zobrazit u galerie (stejné pravidlo jako u {@link ExternalLink} a {@link GeocodeResult}).
 *
 * <p>{@code id} zůstává {@link Long} (odpovídá DB PK, GraphQL {@code ID!} ho stejně přes
 * Spring for GraphQL scalar coercion serializuje jako string bez ohledu na Java typ) —
 * {@code @JsonFormat(shape = STRING)} řeší jen REST cestu ({@code MediaController}, obyčejný
 * Jackson, GraphQL scalar coercion se ho netýká), kde by jinak Long zůstal syrové JSON číslo.
 * Mobilní klient (`network/Dto.kt`, `Photo.id: String`) na string napevno spoléhá bez GraphQL
 * codegenu — bez téhle anotace kotlinx.serialization na tom nesouhlasu shazovalo celý upload
 * i po úspěšném uložení na serveru (appka ukázala obecné "Něco se pokazilo", ačkoli fotka už
 * byla nahraná).
 */
public record Photo(@JsonFormat(shape = JsonFormat.Shape.STRING) Long id, String url, String thumbnailUrl,
                     int width, int height, String caption, boolean mine, boolean hidden, String attribution) {
}
