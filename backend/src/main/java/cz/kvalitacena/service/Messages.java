package cz.kvalitacena.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Tenká fasáda nad {@link MessageSource} + {@link LocaleContextHolder}, ať se to neopakuje
 * v {@code GraphQlExceptionHandler}, {@code GlobalExceptionHandler}, {@code SmtpOtpMailSender}
 * a dalších místech, která potřebují lokalizovaný text (docs/lokalizace.md).
 */
@Service
@RequiredArgsConstructor
public class Messages {

  private final MessageSource messageSource;

  /** Použije locale aktuálního requestu (nastavuje ho UserAwareLocaleResolver). */
  public String get(String key, Object... args) {
    return get(key, LocaleContextHolder.getLocale(), args);
  }

  public String get(String key, Locale locale, Object... args) {
    return messageSource.getMessage(key, args, locale);
  }
}
