package cz.kvalitacena.i18n;

import cz.kvalitacena.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hlídá, že jazykové varianty bundlů (docs/lokalizace.md) nezaostávají za českým základem.
 * {@code MessageSource} s {@code useCodeAsDefaultMessage=false} by chybějící klíč odhalil až
 * při reálném requestu v daném jazyce — tenhle test to zjistí dřív, při každém buildu.
 */
class MessageBundleTest {

  private static final List<String> BUNDLES = List.of("errors", "mail", "handles", "attribution");
  private static final List<String> LANGUAGES = List.of("sk", "en", "pl");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[,}]");

  @Test
  void everyLanguageHasSameKeysAsCzechBase() {
    for (String bundle : BUNDLES) {
      Set<String> base = load(bundle, null).stringPropertyNames();
      for (String lang : LANGUAGES) {
        Set<String> translated = load(bundle, lang).stringPropertyNames();
        assertThat(translated)
            .as("%s_%s.properties vs. %s.properties (český základ)", bundle, lang, bundle)
            .containsExactlyInAnyOrderElementsOf(base);
      }
    }
  }

  @Test
  void everyErrorCodeHasAMessageKeyInCzechBase() {
    Properties errors = load("errors", null);
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(errors.getProperty(code.getMessageKey()))
          .as("errors.properties musí mít klíč %s pro ErrorCode.%s", code.getMessageKey(), code)
          .isNotNull();
    }
  }

  /**
   * Placeholdery ({0}, {1}, ...) se při překladu snadno ztratí — {@link java.text.MessageFormat}
   * chybějící parametr tiše vynechá místo pádu, takže se to bez tohohle testu odhalí až vizuálně.
   */
  @Test
  void placeholderCountMatchesAcrossLanguages() {
    for (String bundle : BUNDLES) {
      Properties base = load(bundle, null);
      for (String lang : LANGUAGES) {
        Properties translated = load(bundle, lang);
        for (String key : base.stringPropertyNames()) {
          String translatedValue = translated.getProperty(key);
          if (translatedValue == null) {
            continue; // chybějící klíč hlásí everyLanguageHasSameKeysAsCzechBase
          }
          assertThat(placeholders(translatedValue))
              .as("%s_%s.properties[%s] má jiné placeholdery než český základ", bundle, lang, key)
              .isEqualTo(placeholders(base.getProperty(key)));
        }
      }
    }
  }

  private Set<Integer> placeholders(String value) {
    Set<Integer> found = new TreeSet<>();
    Matcher matcher = PLACEHOLDER.matcher(value);
    while (matcher.find()) {
      found.add(Integer.valueOf(matcher.group(1)));
    }
    return found;
  }

  private Properties load(String bundle, String lang) {
    String suffix = lang == null ? "" : "_" + lang;
    String resource = "messages/" + bundle + suffix + ".properties";
    Properties properties = new Properties();
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as("resource %s musí existovat na classpath", resource).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }
}
