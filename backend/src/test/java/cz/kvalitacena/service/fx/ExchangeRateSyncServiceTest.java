package cz.kvalitacena.service.fx;

import cz.kvalitacena.config.FxProperties;
import cz.kvalitacena.db.entity.ExchangeRate;
import cz.kvalitacena.db.entity.ExchangeRateId;
import cz.kvalitacena.db.repo.ExchangeRateRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backfill/catch-up logika kurzovního lístku (docs/lokalizace.md) — testuje se přes veřejný
 * vstupní bod {@link ExchangeRateSyncService#sync()}, se zdrojem (ČNB) i repozitáři mockovanými,
 * stejný vzorec jako {@code PriceAggregationServiceTest}. Většina testů má jen jeden zdroj v
 * seznamu (ekvivalent ČNB) — sloučení víc zdrojů (ČNB + NBS pro RSD) ověřuje samostatně
 * {@link #mergesRowsFromMultipleSourcesAndTagsEachWithItsSource}.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRateSyncServiceTest {

  private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Prague"));

  @Mock
  private ExchangeRateSource source;
  @Mock
  private ExchangeRateRepository exchangeRateRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;

  @Captor
  private ArgumentCaptor<ExchangeRate> savedCaptor;

  private FxProperties fxProperties;
  private ExchangeRateSyncService service;

  @BeforeEach
  void setUp() {
    fxProperties = new FxProperties();
    fxProperties.setEnabled(true);
    fxProperties.setZone("Europe/Prague");
    fxProperties.setTrackedCurrencies(List.of("EUR", "PLN", "USD"));
    fxProperties.setMaxBackfillYears(5);
    Mockito.lenient().when(source.name()).thenReturn("CNB");
    service = new ExchangeRateSyncService(List.of(source), exchangeRateRepository, priceObservationRepository, fxProperties);
  }

  @Test
  void syncDoesNothingWhenDisabled() {
    fxProperties.setEnabled(false);

    service.sync();

    verify(exchangeRateRepository, never()).findTopByOrderByRateDateDesc();
  }

  @Test
  void emptyTableWithNoObservationsFetchesOnlyToday() {
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt()).thenReturn(Optional.empty());
    when(source.fetchDay(TODAY)).thenReturn(List.of(
        new ExchangeRateSource.FxRateRow("EUR", 1, TODAY, new BigDecimal("24.255"))));
    when(exchangeRateRepository.existsById(any())).thenReturn(false);

    service.sync();

    verify(source).fetchDay(TODAY);
    verify(source, never()).fetchYear(anyInt());
  }

  /** Backfill zarovná na začátek roku a stáhne roční endpoint pro každý rok mezi nejstarší cenou a dneškem. */
  @Test
  void emptyTableBackfillsFromEarliestObservationAlignedToJanuary() {
    LocalDate earliest = TODAY.minusYears(2).withMonth(6).withDayOfMonth(15);
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt())
        .thenReturn(Optional.of(earliest.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
    when(source.fetchYear(anyInt())).thenReturn(List.of());

    service.sync();

    for (int year = earliest.getYear(); year <= TODAY.getYear(); year++) {
      verify(source).fetchYear(year);
    }
    verify(source, never()).fetchDay(any());
  }

  /** app.fx.max-backfill-years omezuje, jak hluboko appka jde, i když je v core.price_observation starší cena. */
  @Test
  void backfillIsCappedByMaxBackfillYears() {
    LocalDate veryOld = TODAY.minusYears(20);
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt())
        .thenReturn(Optional.of(veryOld.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
    when(source.fetchYear(anyInt())).thenReturn(List.of());

    service.sync();

    int expectedFirstYear = TODAY.minusYears(5).getYear();
    verify(source, never()).fetchYear(veryOld.getYear());
    verify(source).fetchYear(expectedFirstYear);
    verify(source).fetchYear(TODAY.getYear());
  }

  /** Malá mezera (≤30 dní) jde po dnech, ne roční endpoint. */
  @Test
  void catchUpWithSmallGapUsesDailyEndpoint() {
    LocalDate lastKnown = TODAY.minusDays(2);
    when(exchangeRateRepository.findTopByOrderByRateDateDesc())
        .thenReturn(Optional.of(rateOn(lastKnown)));
    when(source.fetchDay(any())).thenReturn(List.of());

    service.sync();

    verify(source, times(2)).fetchDay(any()); // lastKnown+1 a lastKnown+2 (= dnešek)
    verify(source, never()).fetchYear(anyInt());
  }

  @Test
  void catchUpAlreadyUpToDateDoesNothing() {
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rateOn(TODAY)));

    service.sync();

    verify(source, never()).fetchDay(any());
    verify(source, never()).fetchYear(anyInt());
    verify(exchangeRateRepository, never()).save(any());
  }

  /** Sledují se jen app.fx.tracked-currencies — HUF/jiné se zahodí, i kdyby je ČNB vrátila. */
  @Test
  void savesOnlyTrackedCurrenciesAndNormalizesRateByAmount() {
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt()).thenReturn(Optional.empty());
    when(source.fetchDay(TODAY)).thenReturn(List.of(
        new ExchangeRateSource.FxRateRow("EUR", 1, TODAY, new BigDecimal("24.255")),
        new ExchangeRateSource.FxRateRow("HUF", 100, TODAY, new BigDecimal("6.668"))));
    when(exchangeRateRepository.existsById(any())).thenReturn(false);

    service.sync();

    verify(exchangeRateRepository, times(1)).save(savedCaptor.capture());
    ExchangeRate saved = savedCaptor.getValue();
    assertThat(saved.getCurrency()).isEqualTo("EUR");
    assertThat(saved.getCzkPerUnit()).isEqualByComparingTo(new BigDecimal("24.255000"));
    assertThat(saved.getSource()).isEqualTo("CNB");
  }

  /**
   * Druhý zdroj (NBS pro RSD, plán expanze) — oba zdroje se dotazují nezávisle, jejich řádky
   * se jen sloučí a každý si nese svoje {@code source} do {@code fx.exchange_rate}, ne že by
   * druhý zdroj přepsal/nahradil první.
   */
  @Test
  void mergesRowsFromMultipleSourcesAndTagsEachWithItsSource() {
    ExchangeRateSource nbs = org.mockito.Mockito.mock(ExchangeRateSource.class);
    Mockito.lenient().when(nbs.name()).thenReturn("NBS");
    ExchangeRateSyncService multiSourceService = new ExchangeRateSyncService(
        List.of(source, nbs), exchangeRateRepository, priceObservationRepository, fxProperties);
    fxProperties.setTrackedCurrencies(List.of("EUR", "RSD"));

    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt()).thenReturn(Optional.empty());
    when(source.fetchDay(TODAY)).thenReturn(List.of(
        new ExchangeRateSource.FxRateRow("EUR", 1, TODAY, new BigDecimal("24.255"))));
    when(nbs.fetchDay(TODAY)).thenReturn(List.of(
        new ExchangeRateSource.FxRateRow("RSD", 1, TODAY, new BigDecimal("0.221"))));
    when(exchangeRateRepository.existsById(any())).thenReturn(false);

    multiSourceService.sync();

    verify(exchangeRateRepository, times(2)).save(savedCaptor.capture());
    var bySource = savedCaptor.getAllValues().stream()
        .collect(java.util.stream.Collectors.toMap(ExchangeRate::getCurrency, ExchangeRate::getSource));
    assertThat(bySource).containsEntry("EUR", "CNB").containsEntry("RSD", "NBS");
  }

  /** Druhý běh nad stejnými daty nesmí založit duplicity — ČNB kurzy zpětně nemění. */
  @Test
  void secondRunIsIdempotent() {
    when(exchangeRateRepository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());
    when(priceObservationRepository.findEarliestObservedAt()).thenReturn(Optional.empty());
    when(source.fetchDay(TODAY)).thenReturn(List.of(
        new ExchangeRateSource.FxRateRow("EUR", 1, TODAY, new BigDecimal("24.255"))));
    when(exchangeRateRepository.existsById(new ExchangeRateId(TODAY, "EUR"))).thenReturn(true);

    service.sync();

    verify(exchangeRateRepository, never()).save(any());
  }

  private ExchangeRate rateOn(LocalDate date) {
    return ExchangeRate.builder().rateDate(date).currency("EUR").czkPerUnit(BigDecimal.TEN)
        .fetchedAt(OffsetDateTime.now()).build();
  }
}
