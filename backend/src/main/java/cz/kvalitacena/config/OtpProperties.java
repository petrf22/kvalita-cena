package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.auth.otp")
@Data
public class OtpProperties {
  /** V etapě 1 (MVP) false — kód se místo odeslání loguje do konzole, viz OtpService. */
  private boolean mailEnabled;
  private Duration codeTtl;
  private Duration resendCooldown;
  private int maxAttempts;
  /**
   * Odesílací adresa pro všechny e-maily (OTP, změna e-mailu, výmaz účtu) — bez ní
   * {@code SmtpOtpMailSender} posílal zprávy bez hlavičky {@code From}, což většina SMTP
   * poskytovatelů s ověřenou odesílací doménou rovnou odmítne (docs/nasazeni.md).
   */
  private String mailFrom;
}
