package cz.kvalitacena.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Skutečné odeslání přes SMTP (spring-boot-starter-mail) — zapíná se app.auth.otp.mail-enabled=true. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auth.otp", name = "mail-enabled", havingValue = "true")
public class SmtpOtpMailSender implements OtpMailSender {

  private final JavaMailSender javaMailSender;

  @Override
  public void sendOtpCode(String email, String code) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setTo(email);
    message.setSubject("Přihlašovací kód — Kvalita a cena");
    message.setText("Tvůj přihlašovací kód je: " + code
        + "\n\nKód platí 10 minut. Pokud sis o něj nežádal(a), tuto zprávu ignoruj.");
    javaMailSender.send(message);
  }
}
