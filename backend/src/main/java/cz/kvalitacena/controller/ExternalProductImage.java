package cz.kvalitacena.controller;

/** Externí obrázek OFF — URL se zobrazuje přímo, obsah se nikdy neukládá do core.media. */
public record ExternalProductImage(String url, String thumbnailUrl, String attribution) {
}
