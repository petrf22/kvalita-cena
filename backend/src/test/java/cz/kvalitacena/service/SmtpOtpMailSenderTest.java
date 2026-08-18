package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Bez {@code From} hlavičky by většina SMTP poskytovatelů s ověřenou odesílací doménou zprávu
 * rovnou odmítla (docs/nasazeni.md) — regrese na tohle chování se objevila při ověřování
 * moderačních nástrojů v prohlížeči, kód sám nikdy `setFrom` nevolal.
 */
@ExtendWith(MockitoExtension.class)
class SmtpOtpMailSenderTest {

  private static final String FROM = "KvalitaACena <kontakt@kvalitacena.cz>";

  @Mock
  private JavaMailSender javaMailSender;
  @Mock
  private Messages messages;

  private SmtpOtpMailSender sender;

  @BeforeEach
  void setUp() {
    OtpProperties otpProperties = new OtpProperties();
    otpProperties.setMailFrom(FROM);
    otpProperties.setCodeTtl(Duration.ofMinutes(10));
    sender = new SmtpOtpMailSender(javaMailSender, messages, otpProperties);
  }

  private SimpleMailMessage sentMessage() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(captor.capture());
    return captor.getValue();
  }

  @Test
  void otpCodeMailHasFromAddress() {
    sender.sendOtpCode("uzivatel@example.com", "123456", Locale.forLanguageTag("cs"));

    assertThat(sentMessage().getFrom()).isEqualTo(FROM);
  }

  @Test
  void emailChangeCodeMailHasFromAddress() {
    sender.sendEmailChangeCode("uzivatel@example.com", "123456", Locale.forLanguageTag("cs"));

    assertThat(sentMessage().getFrom()).isEqualTo(FROM);
  }

  @Test
  void emailChangeConflictMailHasFromAddress() {
    sender.sendEmailChangeConflict("uzivatel@example.com", Locale.forLanguageTag("cs"));

    assertThat(sentMessage().getFrom()).isEqualTo(FROM);
  }

  @Test
  void accountDeleteCodeMailHasFromAddress() {
    sender.sendAccountDeleteCode("uzivatel@example.com", "123456", Locale.forLanguageTag("cs"));

    assertThat(sentMessage().getFrom()).isEqualTo(FROM);
  }
}
