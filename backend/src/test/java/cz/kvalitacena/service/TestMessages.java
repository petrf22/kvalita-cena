package cz.kvalitacena.service;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Reálná {@link Messages} instance pro jednotkové testy mimo Spring kontext — stejné basenames
 * jako {@code I18nConfig.messageSource()}, jen bez Spring beanu. Testy tak ověřují proti
 * skutečnému obsahu bundlů, ne proti duplicitně opsanému řetězci.
 */
public final class TestMessages {

  private static final Messages INSTANCE = build();

  private TestMessages() {
  }

  public static Messages instance() {
    return INSTANCE;
  }

  private static Messages build() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasenames("messages/errors", "messages/mail", "messages/enums",
        "messages/handles", "messages/attribution");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    source.setUseCodeAsDefaultMessage(false);
    return new Messages(source);
  }
}
