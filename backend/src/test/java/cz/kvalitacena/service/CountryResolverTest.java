package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.CountryInfo;
import cz.kvalitacena.db.repo.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CountryResolver#supportedCountries} — číselník pro {@code Query.countries}
 * (docs/lokalizace.md, "Country selector v UI"). Používá reálný {@link ResourceBundleMessageSource}
 * nad {@code messages/countries*.properties}, ne mock — právě proto, aby test spadl, kdyby
 * appce (třeba po přidání další země do app.i18n.country-currency) chyběl překladový klíč,
 * stejně jako {@code MessageBundleTest}.
 */
@ExtendWith(MockitoExtension.class)
class CountryResolverTest {

  @Mock
  private AppUserRepository appUserRepository;

  private CountryResolver resolver;

  @BeforeEach
  void setUp() {
    I18nProperties i18nProperties = new I18nProperties();
    i18nProperties.setDefaultCountry("CZ");
    i18nProperties.setCountryCurrency(Map.ofEntries(
        Map.entry("CZ", "CZK"), Map.entry("SK", "EUR"), Map.entry("PL", "PLN"), Map.entry("DE", "EUR"),
        Map.entry("AT", "EUR"), Map.entry("FR", "EUR"), Map.entry("ES", "EUR"), Map.entry("IT", "EUR"),
        Map.entry("HR", "EUR"), Map.entry("SI", "EUR"), Map.entry("BG", "EUR"), Map.entry("HU", "HUF"),
        Map.entry("RO", "RON"), Map.entry("GB", "GBP"), Map.entry("CH", "CHF"), Map.entry("RS", "RSD")));
    i18nProperties.setCountryLocale(Map.of("CZ", "cs", "SK", "sk", "PL", "pl"));

    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasenames("messages/countries");
    messageSource.setDefaultEncoding("UTF-8"); // stejně jako I18nConfig.messageSource()
    messageSource.setFallbackToSystemLocale(false);
    Messages messages = new Messages(messageSource);

    resolver = new CountryResolver(i18nProperties, appUserRepository, messages);
  }

  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  /** Nezávisle na JVM default locale CI stroje — bez explicitního nastavení by test byl nespolehlivý. */
  @Test
  void everyConfiguredCountryHasALocalizedNameInEveryLanguage() {
    for (String lang : List.of("cs", "sk", "en", "pl")) {
      LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
      var countries = resolver.supportedCountries();

      assertThat(countries).as("jazyk %s", lang).hasSize(16);
      assertThat(countries).as("jazyk %s", lang).allSatisfy(c -> assertThat(c.name()).isNotBlank());
    }
  }

  @Test
  void namesAreSortedByCodeAndLocalized() {
    LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
    var countries = resolver.supportedCountries();

    assertThat(countries).extracting(CountryInfo::code).isSorted();
    CountryInfo cz = countries.stream().filter(c -> c.code().equals("CZ")).findFirst().orElseThrow();
    assertThat(cz.name()).isEqualTo("Česko");
    assertThat(cz.currency()).isEqualTo("CZK");
  }
}
