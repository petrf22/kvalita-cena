package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fotky zboží a provozoven (core.media, MediaStorage) — binární obsah leží mimo databázi,
 * {@code root} je adresář na disku (v produkci mimo repozitář, zálohuje se nezávisle na
 * {@code pg_dump}, viz docs/datovy-model.md). Limity patří sem, ne natvrdo do kódu, ze
 * stejného důvodu jako {@link CatalogProperties}/{@link ModerationProperties}.
 */
@Component
@ConfigurationProperties(prefix = "app.media")
@Data
public class MediaProperties {
  private String root;
  /** Maximální velikost nahrávaného souboru (před zpracováním) v bajtech. */
  private long maxUploadBytes;
  /**
   * Pojistka proti "decompression bomb" — kontroluje se z hlavičky PŘED dekódováním obrázku
   * (ImageProcessingService), aby malý soubor s obřími rozměry nedokázal alokovat gigabajty.
   */
  private long maxPixels;
  /** Delší strana uloženého obrázku po zmenšení (jen dolů, nikdy nezvětšuje). */
  private int maxDimension;
  private int thumbnailDimension;
  private int maxPhotosPerRecord;
  private int maxUploadsPerDay;
}
