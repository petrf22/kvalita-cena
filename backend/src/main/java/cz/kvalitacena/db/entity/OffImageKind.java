package cz.kvalitacena.db.entity;

/**
 * Druh fotky ve snapshotu Open Food Facts ({@code off.product_image.kind}) — protějšek
 * {@link PhotoKind} na naší straně: {@code FRONT} odpovídá {@link PhotoKind#ITEM},
 * {@code INGREDIENTS} odpovídá {@link PhotoKind#LABEL}. Vlastní enum, ne sdílený
 * {@link PhotoKind}, protože jde o hodnoty CIZÍHO schématu ({@code selected_images.front} /
 * {@code .ingredients} v OFF API) — kdyby OFF přidal další druh, nesmí to tlačit na význam
 * našeho {@code core.media.photo_kind}.
 *
 * <p>{@code nutrition} z OFF se schválně neukládá — appka nutriční tabulku nikde nezobrazuje,
 * takže by šlo o cizí data bez čtenáře.
 */
public enum OffImageKind {
  FRONT,
  INGREDIENTS;

  /** Jak se druh z OFF mapuje na osu, kterou používají vlastní fotky (core.media). */
  public PhotoKind toPhotoKind() {
    return this == FRONT ? PhotoKind.ITEM : PhotoKind.LABEL;
  }
}
