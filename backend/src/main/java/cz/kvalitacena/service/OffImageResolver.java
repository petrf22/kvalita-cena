package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.db.entity.OffImageKind;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.OffProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Výběr fotky z OFF podle jazyka obalu — protějšek {@link ProductNameResolver} pro
 * {@code selected_images}. Obal i etiketa jsou vyfocený TEXT, takže platí stejné pravidlo jako
 * u názvu: nejdřív jazyk klienta, a teprve když v něm fotka není, sáhne se jinam (cizojazyčná
 * etiketa je pořád lepší než žádná).
 *
 * <p>Poslední článek je {@code off.product.image_front_url} — „hlavní" přední fotka
 * ze snapshotů stažených dřív, než se {@code selected_images} vůbec ukládaly. Jazyk u ní
 * neznáme, takže zůstane {@code null} a klient ji neoznačí.
 */
@Service
@RequiredArgsConstructor
public class OffImageResolver {

  private final I18nProperties i18nProperties;

  /** Všechny fotky ze snapshotu, jazyk requestu první, pak hlavní jazyk OFF, pak abecedně. */
  public List<OffProductImage> all(OffProduct off) {
    if (off == null || off.getImages().isEmpty()) return legacyFront(off);
    List<OffProductImage> images = new ArrayList<>(off.getImages());
    images.sort(Comparator.comparingInt((OffProductImage image) -> preference(off, image.getLang()))
        .thenComparing(OffProductImage::getKind)
        .thenComparing(OffProductImage::getLang));
    return List.copyOf(images);
  }

  /** Fotka daného druhu pro jazyk klienta, jinak nejbližší náhrada. */
  public Optional<OffProductImage> preferred(OffProduct off, OffImageKind kind) {
    return all(off).stream().filter(image -> image.getKind() == kind).findFirst();
  }

  /** Čím nižší číslo, tím dřív se fotka nabídne. */
  private int preference(OffProduct off, String lang) {
    if (lang == null) return 4;
    if (lang.equals(LocaleContextHolder.getLocale().getLanguage())) return 0;
    if (lang.equals(off.getLang())) return 1;
    if (i18nProperties.getDefaultLocale() != null
        && lang.equals(Locale.forLanguageTag(i18nProperties.getDefaultLocale()).getLanguage())) {
      return 2;
    }
    return 3;
  }

  private List<OffProductImage> legacyFront(OffProduct off) {
    if (off == null || off.getImageFrontUrl() == null) return List.of();
    return List.of(OffProductImage.builder().kind(OffImageKind.FRONT).lang(null)
        .url(off.getImageFrontUrl()).smallUrl(off.getImageFrontSmallUrl()).build());
  }
}
