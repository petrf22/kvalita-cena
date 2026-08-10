package cz.kvalitacena.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hlídá, že do service/controller vrstvy se nevrátí zadrátovaný český text ve výjimce — dřív
 * hlavní zdroj nelokalizovaných chyb (docs/lokalizace.md, kontrakt chyby). Nekontroluje logy
 * (CLAUDE.md — logy jsou schválně česky) ani technické výjimky v {@link #ALLOWLIST} (coercion
 * chyby GraphQL scalarů, IO/hash chyby, které se uživateli nikdy nezobrazí jako lokalizovaný
 * text — viz komentář u každého souboru v historii, kde se do bílé listiny dostal).
 */
class HardcodedTextTest {

  private static final Path SOURCE_ROOT = Path.of("src/main/java");

  /** Soubory s technickými výjimkami, které se uživateli nikdy nezobrazí jako lokalizovaný text. */
  private static final Set<String> ALLOWLIST = Set.of(
      "GraphQlScalars.java", // coercion chyby jdou do introspekce/protokolu, ne uživateli
      "ProductSearchRepositoryImpl.java", // IllegalStateException nad neočekávaným JDBC typem — programátorská chyba
      "MediaController.java", // UncheckedIOException při čtení uploadu — technická I/O chyba
      "LocalFileSystemMediaStorage.java", // totéž pro ukládání na disk
      "EmailCipher.java", // hash/šifrování e-mailu selže jen při programátorské/konfigurační chybě
      "ImageProcessingService.java" // zápis na disk / chybějící SHA-256 — technické, ne doménové
  );

  private static final Pattern HARDCODED_EXCEPTION_TEXT = Pattern.compile(
      "throw new \\w*Exception\\(\\s*\"[^\"]*[\\u011B\\u0161\\u010D\\u0159\\u017E"
          + "\\u00FD\\u00E1\\u00ED\\u00E9\\u00FA\\u016F\\u0165\\u010F\\u0148"
          + "\\u011A\\u0160\\u010C\\u0158\\u017D\\u00DD\\u00C1\\u00CD\\u00C9\\u00DA\\u016E\\u0164\\u010E\\u0147]");

  @Test
  void serviceLayerDoesNotHardcodeCzechExceptionText() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !ALLOWLIST.contains(path.getFileName().toString()))
          .forEach(path -> collectViolations(path, violations));
    }
    assertThat(violations)
        .as("Nový český text natvrdo ve výjimce mimo ALLOWLIST — použij ErrorCode/AppException"
            + " (docs/lokalizace.md), nebo soubor přidej do ALLOWLIST, pokud jde o čistě"
            + " technickou výjimku, kterou uživatel nikdy neuvidí jako lokalizovaný text.")
        .isEmpty();
  }

  private void collectViolations(Path path, List<String> violations) {
    String content;
    try {
      content = Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    int lineNumber = 1;
    for (String line : content.split("\n", -1)) {
      Matcher matcher = HARDCODED_EXCEPTION_TEXT.matcher(line);
      if (matcher.find()) {
        violations.add(SOURCE_ROOT.relativize(path) + ":" + lineNumber + " — " + line.strip());
      }
      lineNumber++;
    }
  }
}
