package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PhotoKind;

/**
 * Externí obrázek OFF — URL se zobrazuje přímo, obsah se nikdy neukládá do core.media.
 *
 * <p>{@code kind} i {@code lang} jsou tatáž osa jako u vlastních fotek ({@link Photo}): obal
 * (ITEM) nebo etiketa se složením (LABEL), a v jakém jazyce je obal na fotce. {@code lang} je
 * {@code null} u snapshotů stažených dřív, než se {@code selected_images} ukládaly.
 */
public record ExternalProductImage(String url, String thumbnailUrl, PhotoKind kind, String lang,
                                    String attribution) {
}
