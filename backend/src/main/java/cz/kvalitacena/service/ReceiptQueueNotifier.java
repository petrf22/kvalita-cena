package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * E-mail provozovateli, že ve frontě čekají účtenky — worker se spouští ručně
 * (docs/ai.md, „Účtenky"). Chodí, když do prázdné fronty dorazí první účtenka, a jednou denně,
 * dokud fronta není prázdná. Bez {@code app.receipt.notify-email} se neposílá nic.
 *
 * <p>Text je jen pro provozovatele, ne pro uživatele, proto česky natvrdo jako logy, ne přes
 * lokalizované {@code messages/mail*.properties}. Nese jen počet a čas nejstarší účtenky —
 * nic o tom, kdo ji nahrál ani co na ní je (docs/soukromi.md, „Účtenky").
 */
@Slf4j
@Service
public class ReceiptQueueNotifier {

  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("d. M. yyyy H:mm");

  private final ReceiptJobRepository jobRepository;
  private final ReceiptProperties receiptProperties;
  private final OtpProperties otpProperties;
  private final ObjectProvider<JavaMailSender> javaMailSender;
  private final TaskExecutor mailExecutor;

  public ReceiptQueueNotifier(ReceiptJobRepository jobRepository, ReceiptProperties receiptProperties,
      OtpProperties otpProperties, ObjectProvider<JavaMailSender> javaMailSender,
      @Qualifier("mailTaskExecutor") TaskExecutor mailExecutor) {
    this.jobRepository = jobRepository;
    this.receiptProperties = receiptProperties;
    this.otpProperties = otpProperties;
    this.javaMailSender = javaMailSender;
    this.mailExecutor = mailExecutor;
  }

  /**
   * Volá {@link ReceiptService#upload} uvnitř své transakce hned po založení úlohy — počet
   * nezpracovaných proto už zahrnuje i ji, takže 1 znamená, že fronta byla prázdná. E-mail jde
   * až po commitu, aby rollback uploadu neohlásil účtenku, která ve frontě není.
   */
  public void onEnqueued() {
    if (!enabled() || jobRepository.countByProcessedAtIsNull() != 1) {
      return;
    }
    OffsetDateTime now = OffsetDateTime.now();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      send(1, now);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        send(1, now);
      }
    });
  }

  @Scheduled(cron = "${app.receipt.notify-reminder-cron}", zone = "${app.receipt.notify-reminder-zone}")
  @Transactional(readOnly = true)
  public void remindPending() {
    if (!enabled()) {
      return;
    }
    long pending = jobRepository.countByProcessedAtIsNull();
    if (pending == 0) {
      return;
    }
    jobRepository.findFirstByProcessedAtIsNullOrderByEnqueuedAtAsc()
        .ifPresent(oldest -> send(pending, oldest.getEnqueuedAt()));
  }

  private boolean enabled() {
    String email = receiptProperties.getNotifyEmail();
    return email != null && !email.isBlank();
  }

  private void send(long pending, OffsetDateTime oldestEnqueuedAt) {
    String oldest = oldestEnqueuedAt
        .atZoneSameInstant(ZoneId.of(receiptProperties.getNotifyReminderZone()))
        .format(TIME_FORMAT);
    String text = "Ve frontě čeká účtenek: " + pending + " (nejstarší nahrána " + oldest + ").\n\n"
        + "Zpracování spustíš na PC:\n"
        + "  python3 tools/uctenky/worker.py --once\n\n"
        + "E-mail chodí, když do prázdné fronty dorazí první účtenka, a jednou denně, dokud fronta "
        + "není prázdná (app.receipt.notify-email, docs/ai.md).";
    // Stejný přepínač jako u OTP — lokálně bez SMTP se zpráva jen zaloguje.
    JavaMailSender sender = javaMailSender.getIfAvailable();
    if (!otpProperties.isMailEnabled() || sender == null) {
      log.info("[DEV] Notifikace fronty účtenek: {}", text);
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(otpProperties.getMailFrom());
    message.setTo(receiptProperties.getNotifyEmail());
    message.setSubject("Účtenky čekají na zpracování — Kvalita a cena");
    message.setText(text);
    // Mimo vlákno requestu/scheduleru, stejně jako OTP (AsyncConfig) — pomalé SMTP nesmí
    // zdržet upload účtenky.
    mailExecutor.execute(() -> {
      try {
        sender.send(message);
      } catch (MailException e) {
        log.error("Notifikace fronty účtenek se neodeslala: {}", e.getMessage(), e);
      }
    });
  }
}
