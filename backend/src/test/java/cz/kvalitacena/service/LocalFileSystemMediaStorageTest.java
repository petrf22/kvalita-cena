package cz.kvalitacena.service;

import cz.kvalitacena.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileSystemMediaStorageTest {

  private LocalFileSystemMediaStorage storage;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    MediaProperties mediaProperties = new MediaProperties();
    mediaProperties.setRoot(tempDir.toString());
    storage = new LocalFileSystemMediaStorage(mediaProperties);
  }

  @Test
  void storedContentRoundTripsForBothVariants() {
    byte[] full = "plna fotka".getBytes(StandardCharsets.UTF_8);
    byte[] thumbnail = "nahled".getBytes(StandardCharsets.UTF_8);

    String storageKey = storage.store(full, thumbnail);

    assertThat(storage.load(storageKey, MediaStorage.Variant.FULL)).contains(full);
    assertThat(storage.load(storageKey, MediaStorage.Variant.THUMBNAIL)).contains(thumbnail);
  }

  @Test
  void deleteRemovesBothVariants() {
    String storageKey = storage.store("a".getBytes(StandardCharsets.UTF_8), "b".getBytes(StandardCharsets.UTF_8));

    storage.delete(storageKey);

    assertThat(storage.load(storageKey, MediaStorage.Variant.FULL)).isEmpty();
    assertThat(storage.load(storageKey, MediaStorage.Variant.THUMBNAIL)).isEmpty();
  }

  @Test
  void deletingUnknownKeyIsNotAnError() {
    storage.delete("2026/08/nejaky-neexistujici-klic.jpg");
    // Nespadlo — idempotentní chování je záměr (viz MediaStorage javadoc).
  }

  @Test
  void loadingNonexistentFileReturnsEmpty() {
    assertThat(storage.load("2026/08/00000000-0000-0000-0000-000000000000.jpg", MediaStorage.Variant.FULL))
        .isEqualTo(Optional.empty());
  }

  @Test
  void loadRejectsKeysOutsideExpectedShapePathTraversal() {
    // storageKey se vždy skládá jen v store() — tohle je pojistka pro cizí/poškozenou hodnotu
    // (např. z DB), ne běžná cesta kódu.
    assertThat(storage.load("../../etc/passwd", MediaStorage.Variant.FULL)).isEmpty();
    assertThat(storage.load("2026/08/../../../etc/passwd.jpg", MediaStorage.Variant.FULL)).isEmpty();
  }
}
