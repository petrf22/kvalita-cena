package cz.kvalitacena.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import cz.kvalitacena.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Klíčová záruka z docs/soukromi.md: nahraná fotka nikdy nedonese EXIF (natož GPS) do
 * úložiště, bez ohledu na to, co poslal klient — {@link ImageProcessingService} to zajišťuje
 * překreslením z pixelů, ne selektivním mazáním tagů (viz javadoc třídy).
 */
class ImageProcessingServiceTest {

  private MediaProperties mediaProperties;
  private ImageProcessingService service;

  @BeforeEach
  void setUp() {
    mediaProperties = new MediaProperties();
    mediaProperties.setMaxUploadBytes(8_000_000);
    mediaProperties.setMaxPixels(50_000_000);
    mediaProperties.setMaxDimension(1600);
    mediaProperties.setThumbnailDimension(400);
    service = new ImageProcessingService(mediaProperties);
  }

  @Test
  void exifGpsIsStrippedAndOrientationIsApplied() throws Exception {
    // Fixture (src/test/resources/media/exif-gps-orientation6.jpg) je 40x20 s EXIF Orientation=6
    // (90° CW) a GPS souřadnicemi — vygenerováno mimo appku (Pillow/piexif), appka EXIF nikdy
    // sama nezapisuje.
    byte[] raw = readFixture("media/exif-gps-orientation6.jpg");

    ImageProcessingService.ProcessedImage processed = service.process(raw);

    // Orientace 6 otočí 40x20 na 20x40 — oba rozměry se vejdou pod maxDimension/
    // thumbnailDimension, takže se dál nezmenšuje.
    assertThat(processed.width()).isEqualTo(20);
    assertThat(processed.height()).isEqualTo(40);

    assertNoExifMetadata(processed.full());
    assertNoExifMetadata(processed.thumbnail());
  }

  @Test
  void rejectsUnknownFormat() {
    byte[] notAnImage = "tohle neni obrazek".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> service.process(notAnImage)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedUpload() throws IOException {
    mediaProperties.setMaxUploadBytes(10);
    byte[] raw = createJpeg(40, 20);
    assertThatThrownBy(() -> service.process(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTooManyPixels() throws IOException {
    mediaProperties.setMaxPixels(100); // 40 × 20 = 800 pixelů, přes limit
    byte[] raw = createJpeg(40, 20);
    assertThatThrownBy(() -> service.process(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void scalesDownToMaxDimension() throws IOException {
    mediaProperties.setMaxDimension(50);
    byte[] raw = createJpeg(200, 100);

    ImageProcessingService.ProcessedImage processed = service.process(raw);

    assertThat(processed.width()).isEqualTo(50);
    assertThat(processed.height()).isEqualTo(25);
  }

  @Test
  void neverUpscalesSmallImages() throws IOException {
    byte[] raw = createJpeg(10, 8);

    ImageProcessingService.ProcessedImage processed = service.process(raw);

    assertThat(processed.width()).isEqualTo(10);
    assertThat(processed.height()).isEqualTo(8);
  }

  @Test
  void acceptsPngInput() throws IOException {
    BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(image, "png", baos);

    ImageProcessingService.ProcessedImage processed = service.process(baos.toByteArray());

    assertThat(processed.width()).isEqualTo(30);
    assertThat(processed.height()).isEqualTo(20);
  }

  private void assertNoExifMetadata(byte[] jpegBytes) throws Exception {
    Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpegBytes));
    assertThat(metadata.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
    ExifIFD0Directory exifIfd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
    if (exifIfd0 != null) {
      assertThat(exifIfd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)).isFalse();
    }
  }

  private byte[] createJpeg(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, width, height);
    g.dispose();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", baos);
    return baos.toByteArray();
  }

  private byte[] readFixture(String classpathPath) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
      if (in == null) {
        throw new IOException("Fixture nenalezena na classpath: " + classpathPath);
      }
      return in.readAllBytes();
    }
  }
}
