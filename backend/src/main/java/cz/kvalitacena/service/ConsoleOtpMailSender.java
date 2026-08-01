package cz.kvalitacena.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Etapa 1 (MVP): kód se místo odeslání loguje do konzole — viz app.auth.otp.mail-enabled. */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.auth.otp", name = "mail-enabled", havingValue = "false", matchIfMissing = true)
public class ConsoleOtpMailSender implements OtpMailSender {

  @Override
  public void sendOtpCode(String email, String code) {
    log.info("[DEV] Přihlašovací kód pro {}: {}", email, code);
  }
}
