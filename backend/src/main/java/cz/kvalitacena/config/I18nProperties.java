package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Jazyky a mapa země→měna→jazyk — jeden zdroj pravdy pro {@link cz.kvalitacena.service.CurrencyResolver}
 * a {@code UserAwareLocaleResolver} (docs/lokalizace.md). Rozšíření o další jazyk/zemi je jen
 * dopsání řádku sem + Liquibase CHECK constraint na `currency`/`country_locale`, ne zásah do kódu.
 */
@Component
@ConfigurationProperties(prefix = "app.i18n")
@Data
public class I18nProperties {
  private String defaultLocale;
  private List<String> supportedLocales;
  private String defaultCountry;
  private Map<String, String> countryCurrency;
  private Map<String, String> countryLocale;
}
