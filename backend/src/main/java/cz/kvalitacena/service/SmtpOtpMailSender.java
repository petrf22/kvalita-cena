package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Skutečné odeslání přes SMTP (spring-boot-starter-mail) — zapíná se app.auth.otp.mail-enabled=true.
 *
 * <p>{@code @Async} na všech čtyřech metodách — volající (OtpService.requestOtp, EmailChangeService,
 * AccountService) je nesmí čekat synchronně uvnitř DB transakce, jinak pomalé/nedostupné SMTP
 * zablokuje celý request (viz {@link cz.kvalitacena.config.AsyncConfig}). E-mail se tak může
 * odeslat těsně před commitem obalující transakce — vědomý kompromis, kód platí 10 minut a
 * uživatel ho čte později, ne v tu samou milisekundu.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auth.otp", name = "mail-enabled", havingValue = "true")
public class SmtpOtpMailSender implements OtpMailSender {

  private final JavaMailSender javaMailSender;
  private final Messages messages;
  private final OtpProperties otpProperties;

  @Override
  @Async("mailTaskExecutor")
  public void sendOtpCode(String email, String code, Locale locale) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(otpProperties.getMailFrom());
    message.setTo(email);
    message.setSubject(messages.get("mail.otp.subject", locale));
    // Platnost se čte z konfigurace, ne natvrdo — dřív text tvrdil "10 minut" bez ohledu na
    // skutečnou hodnotu app.auth.otp.code-ttl.
    message.setText(messages.get("mail.otp.body", locale, code, otpProperties.getCodeTtl().toMinutes()));
    javaMailSender.send(message);
  }

  @Override
  @Async("mailTaskExecutor")
  public void sendEmailChangeCode(String email, String code, Locale locale) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(otpProperties.getMailFrom());
    message.setTo(email);
    message.setSubject(messages.get("mail.emailChange.subject", locale));
    message.setText(messages.get("mail.emailChange.body", locale, code, otpProperties.getCodeTtl().toMinutes()));
    javaMailSender.send(message);
  }

  @Override
  @Async("mailTaskExecutor")
  public void sendEmailChangeConflict(String email, Locale locale) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(otpProperties.getMailFrom());
    message.setTo(email);
    message.setSubject(messages.get("mail.emailChange.conflictSubject", locale));
    message.setText(messages.get("mail.emailChange.conflictBody", locale));
    javaMailSender.send(message);
  }

  @Override
  @Async("mailTaskExecutor")
  public void sendAccountDeleteCode(String email, String code, Locale locale) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(otpProperties.getMailFrom());
    message.setTo(email);
    message.setSubject(messages.get("mail.accountDelete.subject", locale));
    message.setText(messages.get("mail.accountDelete.body", locale, code, otpProperties.getCodeTtl().toMinutes()));
    javaMailSender.send(message);
  }
}
