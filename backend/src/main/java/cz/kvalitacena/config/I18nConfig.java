package cz.kvalitacena.config;

import cz.kvalitacena.db.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Backendová i18n infrastruktura — viz docs/lokalizace.md. Základní soubor bundlu (bez
 * jazykové přípony) je ČESKY: {@code cs} je zdrojový jazyk i fallback, takže neznámý
 * {@code Accept-Language} spadne na češtinu bez ohledu na locale serveru
 * ({@link #messageSource} má {@code fallbackToSystemLocale=false} právě proto — jinak by na
 * anglicky nastaveném serveru vyhrála angličtina místo české fallback varianty).
 */
@Configuration
@RequiredArgsConstructor
public class I18nConfig {

  private final I18nProperties properties;

  @Bean
  public MessageSource messageSource() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasenames("messages/errors", "messages/mail", "messages/handles",
        "messages/attribution");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    // Chybějící klíč MUSÍ spadnout (NoSuchMessageException), ne tiše vrátit kód jako text —
    // jinak by chybějící překlad prošel nepovšimnutý až do produkce.
    source.setUseCodeAsDefaultMessage(false);
    return source;
  }

  /**
   * Vrací konkrétní třídu, ne rozhraní {@link LocaleResolver} — {@code ViewerGraphQlController}
   * potřebuje i {@link UserAwareLocaleResolver#invalidate}, který na rozhraní není. Spring
   * Bootu jako implementaci {@code LocaleResolver} stačí, že třída rozhraní implementuje.
   */
  @Bean
  public UserAwareLocaleResolver localeResolver(AppUserRepository appUserRepository) {
    return new UserAwareLocaleResolver(appUserRepository, properties);
  }

  @Bean
  public LocalValidatorFactoryBean validator(MessageSource messageSource) {
    LocalValidatorFactoryBean bean = new LocalValidatorFactoryBean();
    bean.setValidationMessageSource(messageSource);
    return bean;
  }
}
