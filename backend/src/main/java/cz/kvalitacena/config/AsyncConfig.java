package cz.kvalitacena.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;

/**
 * Zapíná {@code @Async} — výchozí konzument je {@link cz.kvalitacena.service.SmtpOtpMailSender}
 * (OTP e-mail, změna e-mailu, výmaz účtu), aby pomalé/nedostupné SMTP nezablokovalo HTTP request
 * ani DB transakci kolem něj (viz OtpService.requestOtp — testování ukázalo, že uživatel dostane
 * "nepodařilo se odeslat kód" i v případě, kdy se e-mail nakonec v pořádku odešle, jen pozdě).
 * Vedle něj {@link #receiptTaskExecutor()} pro přepočet návrhu obchodu u účtenek.
 *
 * <p>Vlastní {@link ThreadPoolTaskExecutor}, ne výchozí {@code SimpleAsyncTaskExecutor} — ten by
 * pro každé volání vytvořil nové vlákno bez limitu, což by při výpadku SMTP (fronta requestů
 * čekajících na timeout) mohlo appku vyhladovět o vlákna.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

  @Override
  @Bean(name = "mailTaskExecutor")
  public ThreadPoolTaskExecutor getAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("mail-async-");
    executor.initialize();
    return executor;
  }

  /**
   * Přepočet návrhu obchodu ostatním účtenkám vlastníka po potvrzení obchodu
   * ({@link cz.kvalitacena.service.ReceiptStoreResuggester}) — čeká na zámky účtenek a páruje
   * řádky (i s dotazy do Open Food Facts), takže ve vlákně requestu by odpověď na už uložené
   * potvrzení mohla přetéct timeout klienta.
   *
   * <p>Jedno vlákno záměrně: přepočty se neprolínají a čekání na zámky neblokuje víc spojení
   * z poolu. Přepočet je idempotentní (počítá vždy z aktuálního stavu), pořadí úloh tedy
   * nerozhoduje — rozhoduje jen, že po posledním potvrzení nějaká proběhne.
   */
  @Bean(name = "receiptTaskExecutor")
  public ThreadPoolTaskExecutor receiptTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("receipt-resuggest-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    executor.initialize();
    return executor;
  }

  /**
   * {@code @Async} metody s návratovým typem {@code void} (všechny čtyři v {@code OtpMailSender})
   * výjimku jinak potichu spolknou — bez tohohle by výpadek SMTP nešel z logu vůbec poznat.
   * Text/parametry výjimky NESMÍ obsahovat kód ani e-mail, jen technickou zprávu selhání.
   */
  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (Throwable ex, Method method, Object... params) ->
        log.error("Asynchronní volání {} selhalo: {}", method.getName(), ex.getMessage(), ex);
  }
}
