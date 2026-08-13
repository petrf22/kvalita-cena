package cz.kvalitacena.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Etapa 1 (MVP): kód se místo odeslání loguje do konzole — viz app.auth.otp.mail-enabled. */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.auth.otp", name = "mail-enabled", havingValue = "false", matchIfMissing = true)
public class ConsoleOtpMailSender implements OtpMailSender {

  @Override
  public void sendOtpCode(String email, String code, Locale locale) {
    // Log zůstává česky bez ohledu na locale (CLAUDE.md — logy jsou pro vývojáře, ne pro
    // uživatele); locale se předává hlavně kvůli jednotné signatuře s SmtpOtpMailSender.
    log.info("[DEV] Přihlašovací kód pro {} (locale {}): {}", email, locale, code);
  }

  @Override
  public void sendEmailChangeCode(String email, String code, Locale locale) {
    log.info("[DEV] Kód pro změnu přihlašovacího e-mailu na {} (locale {}): {}", email, locale, code);
  }

  @Override
  public void sendEmailChangeConflict(String email, Locale locale) {
    log.info("[DEV] Pokus o změnu e-mailu na už obsazenou adresu {} (locale {})", email, locale);
  }

  @Override
  public void sendAccountDeleteCode(String email, String code, Locale locale) {
    log.info("[DEV] Kód pro výmaz účtu {} (locale {}): {}", email, locale, code);
  }
}
