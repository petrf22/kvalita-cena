package cz.kvalitacena.service.fx;

import cz.kvalitacena.config.FxProperties;
import cz.kvalitacena.db.entity.ExchangeRate;
import cz.kvalitacena.db.repo.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Přepočet měn kurzem ČNB (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") — jádro
 * požadavku je, že se vždy použije kurz PLATNÝ K DANÉMU DNI, nikdy dnešní.
 */
@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

  private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 7);
  private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 9);

  @Mock
  private ExchangeRateRepository exchangeRateRepository;

  private FxProperties fxProperties;
  private FxRateService service;

  @BeforeEach
  void setUp() {
    fxProperties = new FxProperties();
    fxProperties.setCacheTtl(Duration.ofHours(6));
    service = new FxRateService(exchangeRateRepository, fxProperties);
  }

  @Test
  void czkPerUnitOfCzkIsAlwaysOneWithoutTouchingRepository() {
    assertThat(service.czkPerUnit("CZK", SUNDAY)).contains(BigDecimal.ONE);
  }

  @Test
  void czkPerUnitReturnsEmptyWhenNoRateIsKnown() {
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(eq("EUR"), any()))
        .thenReturn(Optional.empty());

    assertThat(service.czkPerUnit("EUR", SUNDAY)).isEmpty();
  }

  /** ČNB o víkendu nepublikuje — "kurz k neděli" musí vrátit poslední předchozí (pátek) lístek. */
  @Test
  void weekendDateFallsBackToLastPublishedRate() {
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("EUR", SUNDAY))
        .thenReturn(Optional.of(ExchangeRate.builder()
            .rateDate(FRIDAY).currency("EUR").czkPerUnit(new BigDecimal("24.255000")).build()));

    assertThat(service.czkPerUnit("EUR", SUNDAY)).contains(new BigDecimal("24.255000"));
  }

  @Test
  void convertReturnsEmptyForSameCurrency() {
    assertThat(service.convert(BigDecimal.TEN, "EUR", "EUR", SUNDAY)).isEmpty();
  }

  @Test
  void convertReturnsEmptyWhenRateIsMissing() {
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(eq("PLN"), any()))
        .thenReturn(Optional.empty());

    assertThat(service.convert(BigDecimal.TEN, "PLN", "USD", SUNDAY)).isEmpty();
  }

  /** Křížový kurz jde vždy přes CZK jako pivot — PLN → USD musí dát PLN → CZK → USD, ne přímý poměr z tabulky. */
  @Test
  void convertsCrossRateThroughCzkPivot() {
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("PLN", FRIDAY))
        .thenReturn(Optional.of(ExchangeRate.builder()
            .rateDate(FRIDAY).currency("PLN").czkPerUnit(new BigDecimal("5.700000")).build()));
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("USD", FRIDAY))
        .thenReturn(Optional.of(ExchangeRate.builder()
            .rateDate(FRIDAY).currency("USD").czkPerUnit(new BigDecimal("21.000000")).build()));

    Optional<FxRateService.Converted> converted = service.convert(new BigDecimal("100"), "PLN", "USD", FRIDAY);

    assertThat(converted).isPresent();
    // 100 PLN * 5,70 CZK/PLN = 570 CZK; 570 / 21,00 CZK/USD = 27,1429 USD.
    assertThat(converted.get().amount()).isEqualByComparingTo(new BigDecimal("27.1429"));
    assertThat(converted.get().currency()).isEqualTo("USD");
    assertThat(converted.get().rateDate()).isEqualTo(FRIDAY);
  }

  @Test
  void convertToCzkNeedsNoLookupOfCzkItself() {
    when(exchangeRateRepository.findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("EUR", FRIDAY))
        .thenReturn(Optional.of(ExchangeRate.builder()
            .rateDate(FRIDAY).currency("EUR").czkPerUnit(new BigDecimal("24.000000")).build()));

    Optional<FxRateService.Converted> converted = service.convert(new BigDecimal("2"), "EUR", "CZK", FRIDAY);

    assertThat(converted).isPresent();
    assertThat(converted.get().amount()).isEqualByComparingTo(new BigDecimal("48.0000"));
    verify(exchangeRateRepository, org.mockito.Mockito.never())
        .findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(eq("CZK"), any());
  }
}
