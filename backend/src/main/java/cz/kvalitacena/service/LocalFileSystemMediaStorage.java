package cz.kvalitacena.service;

import cz.kvalitacena.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ukládá obě varianty fotky jako soubory pod {@code app.media.root} (mimo databázi, mimo
 * {@code pg_dump} — docs/datovy-model.md). Klíč má tvar {@code yyyy/MM/<uuid>.jpg}, náhled je
 * sousední soubor {@code <uuid>_t.jpg} — obě varianty tak sdílejí jeden {@code storageKey}.
 *
 * <p>Zápis jde přes dočasný soubor + {@link StandardCopyOption#ATOMIC_MOVE}, aby souběžné
 * čtení nikdy nepotkalo napůl zapsaný soubor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileSystemMediaStorage implements MediaStorage {

  // storageKey vzniká VÝHRADNĚ v store() níže — regex je pojistka proti path traversal,
  // kdyby se klíč z DB (nebo omylem z requestu) dostal do load()/delete() nedůvěryhodně.
  private static final Pattern KEY_PATTERN =
      Pattern.compile("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");

  private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

  private final MediaProperties mediaProperties;

  @Override
  public String store(byte[] full, byte[] thumbnail) {
    String dir = OffsetDateTime.now().format(DIR_FORMAT);
    String storageKey = dir + "/" + UUID.randomUUID() + ".jpg";
    try {
      Path fullPath = resolveValidated(storageKey);
      Files.createDirectories(fullPath.getParent());
      writeAtomic(fullPath, full);
      writeAtomic(thumbPath(fullPath), thumbnail);
      return storageKey;
    } catch (IOException e) {
      throw new UncheckedIOException("Uložení fotky selhalo", e);
    }
  }

  @Override
  public Optional<byte[]> load(String storageKey, Variant variant) {
    if (!KEY_PATTERN.matcher(storageKey).matches()) {
      log.warn("Odmítnutý storageKey mimo očekávaný tvar: {}", storageKey);
      return Optional.empty();
    }
    Path path = resolveValidated(storageKey);
    Path target = variant == Variant.THUMBNAIL ? thumbPath(path) : path;
    if (!Files.isRegularFile(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException e) {
      log.warn("Čtení fotky {} selhalo: {}", storageKey, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void delete(String storageKey) {
    if (!KEY_PATTERN.matcher(storageKey).matches()) {
      return;
    }
    Path path = resolveValidated(storageKey);
    deleteQuietly(path);
    deleteQuietly(thumbPath(path));
  }

  private Path resolveValidated(String storageKey) {
    Path root = Path.of(mediaProperties.getRoot()).toAbsolutePath().normalize();
    Path resolved = root.resolve(storageKey).normalize();
    if (!resolved.startsWith(root)) {
      // Nemělo by nastat díky KEY_PATTERN výš — druhá vrstva obrany, ne první.
      throw new IllegalArgumentException("Neplatný storageKey");
    }
    return resolved;
  }

  private Path thumbPath(Path fullPath) {
    String name = fullPath.getFileName().toString().replace(".jpg", "_t.jpg");
    return fullPath.resolveSibling(name);
  }

  private void writeAtomic(Path target, byte[] content) throws IOException {
    Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
    try {
      Files.write(temp, content);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Smazání souboru {} selhalo: {}", path, e.getMessage());
    }
  }
}
