package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;

import java.util.List;

/**
 * {@link I18nProperties} s týmiž jazyky jako {@code application.yml} pro jednotkové testy mimo
 * Spring kontext — protějšek {@link TestMessages}. Jazyky se tu opisují schválně: kdyby se
 * seznam v konfiguraci změnil, test s vlastní kopií na to upozorní, místo aby se tiše svezl.
 */
public final class TestI18n {

  private TestI18n() {
  }

  public static I18nProperties properties() {
    I18nProperties properties = new I18nProperties();
    properties.setDefaultLocale("cs");
    properties.setSupportedLocales(List.of("cs", "sk", "en", "pl", "de"));
    properties.setDefaultCountry("CZ");
    return properties;
  }

  public static ProductNameResolver nameResolver() {
    return new ProductNameResolver(properties());
  }

  public static OffImageResolver imageResolver() {
    return new OffImageResolver(properties());
  }
}
