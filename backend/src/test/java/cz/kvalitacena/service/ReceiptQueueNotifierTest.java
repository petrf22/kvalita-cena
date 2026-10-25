package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.ReceiptJob;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Kdy jde provozovateli e-mail o čekajících účtenkách a že nenese nic o uživateli. */
@ExtendWith(MockitoExtension.class)
class ReceiptQueueNotifierTest {

  private static final String OPERATOR = "provozovatel@example.com";

  @Mock
  private ReceiptJobRepository jobRepository;
  @Mock
  private JavaMailSender javaMailSender;
  @Mock
  private ObjectProvider<JavaMailSender> javaMailSenderProvider;

  private ReceiptProperties receiptProperties;
  private ReceiptQueueNotifier notifier;

  @BeforeEach
  void setUp() {
    receiptProperties = new ReceiptProperties();
    receiptProperties.setNotifyEmail(OPERATOR);
    OtpProperties otpProperties = new OtpProperties();
    otpProperties.setMailEnabled(true);
    otpProperties.setMailFrom("KvalitaACena <kontakt@kvalitacena.cz>");
    lenient().when(javaMailSenderProvider.getIfAvailable()).thenReturn(javaMailSender);
    // Synchronní executor — test ověřuje obsah zprávy, ne vlákno, ve kterém odchází.
    notifier = new ReceiptQueueNotifier(jobRepository, receiptProperties, otpProperties,
        javaMailSenderProvider, Runnable::run);
  }

  private SimpleMailMessage sentMessage() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(captor.capture());
    return captor.getValue();
  }

  @Test
  void firstReceiptInEmptyQueueSendsMail() {
    when(jobRepository.countByProcessedAtIsNull()).thenReturn(1L);

    notifier.onEnqueued();

    SimpleMailMessage message = sentMessage();
    assertThat(message.getTo()).containsExactly(OPERATOR);
    assertThat(message.getText()).contains("účtenek: 1").contains("worker.py --once");
  }

  @Test
  void receiptJoiningNonEmptyQueueSendsNothing() {
    when(jobRepository.countByProcessedAtIsNull()).thenReturn(2L);

    notifier.onEnqueued();

    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void dailyReminderReportsCountAndOldestInLocalTime() {
    when(jobRepository.countByProcessedAtIsNull()).thenReturn(3L);
    ReceiptJob oldest = ReceiptJob.builder()
        .enqueuedAt(OffsetDateTime.of(2026, 10, 25, 7, 15, 0, 0, ZoneOffset.UTC))
        .build();
    when(jobRepository.findFirstByProcessedAtIsNullOrderByEnqueuedAtAsc()).thenReturn(Optional.of(oldest));

    notifier.remindPending();

    // 25. 10. 2026 v 1:00 UTC skončil letní čas, v 7:15 UTC už platí SEČ (+1).
    assertThat(sentMessage().getText()).contains("účtenek: 3").contains("25. 10. 2026 8:15");
  }

  @Test
  void dailyReminderWithEmptyQueueSendsNothing() {
    when(jobRepository.countByProcessedAtIsNull()).thenReturn(0L);

    notifier.remindPending();

    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void withoutNotifyEmailNothingIsQueriedOrSent() {
    receiptProperties.setNotifyEmail("");

    notifier.onEnqueued();
    notifier.remindPending();

    verifyNoInteractions(jobRepository, javaMailSender);
  }
}
