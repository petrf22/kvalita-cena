package cz.kvalitacena.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import cz.kvalitacena.config.MediaProperties;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/**
 * Zpracování nahrané fotky (viz {@link MediaService#upload}) — místo, kde se plní záruka
 * z docs/soukromi.md: výstupní JPEG vzniká PŘEKRESLENÍM Z PIXELŮ do nového {@link BufferedImage},
 * nikdy úpravou vstupního souboru, takže nemá kudy projít žádné metadata včetně EXIF GPS.
 * "Strip EXIF" by šlo zapomenout rozšířit o nově přidaný tag; "přeuložit z pixelů" tenhle
 * problém nemá — výstup prostě žádná metadata nikdy neměl.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessingService {

  private static final float JPEG_QUALITY = 0.85f;

  private final MediaProperties mediaProperties;

  public record ProcessedImage(byte[] full, byte[] thumbnail, int width, int height, byte[] sha256) {
  }

  public ProcessedImage process(byte[] raw) {
    if (raw.length == 0 || raw.length > mediaProperties.getMaxUploadBytes()) {
      throw new ValidationException(ErrorCode.PHOTO_TOO_LARGE);
    }
    requireKnownFormat(raw);
    checkPixelBudget(raw);

    BufferedImage decoded = decode(raw);
    int orientation = readExifOrientation(raw);
    BufferedImage oriented = applyOrientation(decoded, orientation);

    BufferedImage full = redrawAndFit(oriented, mediaProperties.getMaxDimension());
    BufferedImage thumbnail = redrawAndFit(oriented, mediaProperties.getThumbnailDimension());

    byte[] fullBytes = encodeJpeg(full);
    byte[] thumbnailBytes = encodeJpeg(thumbnail);
    return new ProcessedImage(fullBytes, thumbnailBytes, full.getWidth(), full.getHeight(), sha256(raw));
  }

  /** Typ podle magic bytes, ne podle {@code Content-Type} hlavičky — tu klient volně lže. */
  private void requireKnownFormat(byte[] raw) {
    boolean jpeg = raw.length >= 3
        && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8 && (raw[2] & 0xFF) == 0xFF;
    boolean png = raw.length >= 8
        && (raw[0] & 0xFF) == 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47;
    if (!jpeg && !png) {
      throw new ValidationException(ErrorCode.PHOTO_UNSUPPORTED_FORMAT);
    }
  }

  /**
   * Rozměry z hlavičky PŘED dekódováním pixelů — pojistka proti "decompression bomb" (malý
   * soubor s obřími rozměry, který by při dekódování zabral gigabajty paměti).
   */
  private void checkPixelBudget(byte[] raw) {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
      if (iis == null) {
        throw new ValidationException(ErrorCode.PHOTO_UNREADABLE);
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        throw new ValidationException(ErrorCode.PHOTO_UNREADABLE);
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis);
        long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
        if (pixels > mediaProperties.getMaxPixels()) {
          throw new ValidationException(ErrorCode.PHOTO_RESOLUTION_TOO_HIGH);
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ValidationException(ErrorCode.PHOTO_UNREADABLE, e);
    }
  }

  private BufferedImage decode(byte[] raw) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
      if (image == null) {
        throw new ValidationException(ErrorCode.PHOTO_UNREADABLE);
      }
      return image;
    } catch (IOException e) {
      throw new ValidationException(ErrorCode.PHOTO_UNREADABLE, e);
    }
  }

  /** 1 (beze změny), pokud EXIF chybí, je nečitelný, nebo tag orientace nemá. */
  private int readExifOrientation(byte[] raw) {
    try {
      Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(raw));
      ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
        return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
      }
    } catch (ImageProcessingException | MetadataException | IOException | RuntimeException e) {
      log.debug("EXIF orientaci se nepodařilo přečíst, použije se výchozí: {}", e.getMessage());
    }
    return 1;
  }

  /** Otočí/zrcadlí obrázek podle EXIF tagu Orientation (hodnoty 1–8, viz EXIF spec). */
  private BufferedImage applyOrientation(BufferedImage src, int orientation) {
    if (orientation <= 1 || orientation > 8) {
      return src;
    }
    int width = src.getWidth();
    int height = src.getHeight();
    boolean swapDimensions = orientation >= 5;
    BufferedImage out = new BufferedImage(
        swapDimensions ? height : width, swapDimensions ? width : height, BufferedImage.TYPE_INT_ARGB);

    AffineTransform t = new AffineTransform();
    switch (orientation) {
      case 2 -> {
        t.scale(-1.0, 1.0);
        t.translate(-width, 0);
      }
      case 3 -> {
        t.translate(width, height);
        t.rotate(Math.PI);
      }
      case 4 -> {
        t.scale(1.0, -1.0);
        t.translate(0, -height);
      }
      case 5 -> {
        t.rotate(Math.PI / 2);
        t.scale(1.0, -1.0);
      }
      case 6 -> {
        t.translate(height, 0);
        t.rotate(Math.PI / 2);
      }
      case 7 -> {
        t.scale(-1.0, 1.0);
        t.translate(-height, 0);
        t.translate(0, width);
        t.rotate(3 * Math.PI / 2);
      }
      case 8 -> {
        t.translate(0, width);
        t.rotate(3 * Math.PI / 2);
      }
      default -> {
      }
    }

    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(src, t, null);
    g.dispose();
    return out;
  }

  /**
   * Vždy vrátí NOVÝ {@link BufferedImage} (i beze změny rozměru) — tenhle překres je krok,
   * který zahodí veškerá metadata včetně EXIF GPS (docs/soukromi.md). Zmenšuje jen dolů,
   * nikdy neupscaluje. Bílé pozadí kvůli PNG s průhledností, která se do JPEGu nevejde.
   */
  private BufferedImage redrawAndFit(BufferedImage src, int maxDimension) {
    int width = src.getWidth();
    int height = src.getHeight();
    double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
    int newWidth = Math.max(1, (int) Math.round(width * scale));
    int newHeight = Math.max(1, (int) Math.round(height * scale));

    BufferedImage out = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, newWidth, newHeight);
    g.drawImage(src, 0, 0, newWidth, newHeight, null);
    g.dispose();
    return out;
  }

  private byte[] encodeJpeg(BufferedImage image) {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(JPEG_QUALITY);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(image, null, null), param);
    } catch (IOException e) {
      throw new UncheckedIOException("Zápis fotky selhal", e);
    } finally {
      writer.dispose();
    }
    return baos.toByteArray();
  }

  private byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 není dostupné", e);
    }
  }
}
