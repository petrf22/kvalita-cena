package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.PriceDailyRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.RecomputeQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Vážený medián a Kishova efektivní velikost vzorku jsou v etapě 1 jediná implementovaná
 * složka reputačního vzorce (docs/reputace.md) — testuje se přes veřejný vstupní bod
 * {@link PriceAggregationService#processQueue()}, ne reflexí na privátní metody.
 */
@ExtendWith(MockitoExtension.class)
class PriceAggregationServiceTest {

  private static final Long PRODUCT_ID = 1L;
  private static final Long STORE_ID = 1L;

  @Mock
  private RecomputeQueueRepository recomputeQueueRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private PriceCurrentRepository priceCurrentRepository;
  @Mock
  private PriceDailyRepository priceDailyRepository;

  @Captor
  private ArgumentCaptor<PriceCurrent> priceCurrentCaptor;

  private PriceAggregationService service;

  private void givenQueuedCell() {
    service = new PriceAggregationService(recomputeQueueRepository, priceObservationRepository,
        priceCurrentRepository, priceDailyRepository);
    RecomputeQueue queued = RecomputeQueue.builder()
        .productId(PRODUCT_ID).storeId(STORE_ID).reason(RecomputeReason.NEW_OBS).build();
    when(recomputeQueueRepository.findTop200ByProcessedAtIsNullOrderByEnqueuedAtAsc())
        .thenReturn(List.of(queued));
    // lenient: testy s prázdným seznamem observací (rejectedObservationsClearExistingDailyRow)
    // nikdy neupsertují agg.price_current, takže findById by tam byl "unnecessary stubbing".
    lenient().when(priceCurrentRepository.findById(any())).thenReturn(Optional.empty());
  }

  private static PriceObservation observation(BigDecimal unitPrice, SubmitterKind submitterKind,
      PriceKind priceKind, OffsetDateTime observedAt) {
    return PriceObservation.builder()
        .unitPrice(unitPrice)
        .priceAmount(unitPrice)
        .submitterKind(submitterKind)
        .priceKind(priceKind)
        .status(ObservationStatus.ACTIVE)
        .observedAt(observedAt)
        .build();
  }

  @Test
  void weightedMedianFavorsRegisteredSubmittersOverAnonymousOutlier() {
    givenQueuedCell();
    OffsetDateTime t1 = OffsetDateTime.parse("2026-07-01T10:00:00Z");
    OffsetDateTime t2 = OffsetDateTime.parse("2026-07-02T10:00:00Z");
    OffsetDateTime latest = OffsetDateTime.parse("2026-07-03T10:00:00Z");

    // Dva registrovaní (váha 1,00) kolem 10-12 Kč, jeden anonym (váha 0,15) s odlehlou
    // hodnotou 20 Kč — anonymní odlehlá hodnota nesmí medián strhnout k sobě.
    List<PriceObservation> observations = List.of(
        observation(new BigDecimal("10"), SubmitterKind.REGISTERED, PriceKind.REGULAR, t1),
        observation(new BigDecimal("12"), SubmitterKind.REGISTERED, PriceKind.REGULAR, t2),
        observation(new BigDecimal("20"), SubmitterKind.ANONYMOUS, PriceKind.REGULAR, latest));
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(observations);

    service.processQueue();

    verify(priceCurrentRepository).save(priceCurrentCaptor.capture());
    PriceCurrent saved = priceCurrentCaptor.getValue();
    assertThat(saved.getUnitPrice()).isEqualByComparingTo("12");
    assertThat(saved.getNObs()).isEqualTo(3);
    assertThat(saved.getSumWeight()).isEqualByComparingTo("2.15");
    assertThat(saved.getLastObservedAt()).isEqualTo(latest);
    // nEff = (Σw)²/Σw² = 2,15² / (1²+1²+0,15²) ≈ 2,29 → MEDIUM (práh 2, pod 5).
    assertThat(saved.getConfidence()).isEqualTo(Confidence.MEDIUM);
  }

  @Test
  void confidenceIsHighWhenEffectiveSampleSizeReachesThreshold() {
    givenQueuedCell();
    OffsetDateTime now = OffsetDateTime.now();

    // Pět registrovaných se stejnou váhou → nEff = 5, přesně na prahu HIGH.
    List<PriceObservation> observations = List.of(
        observation(new BigDecimal("10"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now),
        observation(new BigDecimal("11"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now),
        observation(new BigDecimal("12"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now),
        observation(new BigDecimal("13"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now),
        observation(new BigDecimal("14"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now));
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(observations);

    service.processQueue();

    verify(priceCurrentRepository).save(priceCurrentCaptor.capture());
    PriceCurrent saved = priceCurrentCaptor.getValue();
    assertThat(saved.getUnitPrice()).isEqualByComparingTo("12");
    assertThat(saved.getConfidence()).isEqualTo(Confidence.HIGH);
  }

  @Test
  void regularAndPromoPricesAreAggregatedSeparately() {
    givenQueuedCell();
    OffsetDateTime now = OffsetDateTime.now();

    // Akční a běžná cena tvoří součást klíče agregátu (docs/datovy-model.md) — nesmí se
    // smíchat do jednoho mediánu.
    List<PriceObservation> observations = List.of(
        observation(new BigDecimal("20"), SubmitterKind.REGISTERED, PriceKind.REGULAR, now),
        observation(new BigDecimal("15"), SubmitterKind.REGISTERED, PriceKind.PROMO, now));
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(observations);

    service.processQueue();

    verify(priceCurrentRepository, times(2)).save(priceCurrentCaptor.capture());
    List<PriceCurrent> saved = priceCurrentCaptor.getAllValues();
    assertThat(saved).extracting(PriceCurrent::getPriceKind).containsExactlyInAnyOrder(PriceKind.REGULAR, PriceKind.PROMO);
    assertThat(saved).filteredOn(pc -> pc.getPriceKind() == PriceKind.REGULAR)
        .extracting(PriceCurrent::getUnitPrice).first().isEqualTo(new BigDecimal("20"));
    assertThat(saved).filteredOn(pc -> pc.getPriceKind() == PriceKind.PROMO)
        .extracting(PriceCurrent::getUnitPrice).first().isEqualTo(new BigDecimal("15"));
  }

  @Test
  void dailyAggregateUsesSameWeightsAsCurrentPrice() {
    givenQueuedCell();
    // Stejné rozložení (dva registrovaní + anonymní odlehlá hodnota) jako u
    // weightedMedianFavorsRegisteredSubmittersOverAnonymousOutlier, ale všechno v jednom dni —
    // f_recency se na historii neaplikuje, takže výsledek musí být totožný jako u agg.price_current.
    OffsetDateTime morning = OffsetDateTime.parse("2026-07-01T08:00:00Z");
    OffsetDateTime noon = OffsetDateTime.parse("2026-07-01T12:00:00Z");
    OffsetDateTime evening = OffsetDateTime.parse("2026-07-01T20:00:00Z");
    List<PriceObservation> observations = List.of(
        observation(new BigDecimal("10"), SubmitterKind.REGISTERED, PriceKind.REGULAR, morning),
        observation(new BigDecimal("12"), SubmitterKind.REGISTERED, PriceKind.REGULAR, noon),
        observation(new BigDecimal("20"), SubmitterKind.ANONYMOUS, PriceKind.REGULAR, evening));
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(observations);

    service.processQueue();

    verify(priceDailyRepository).deleteByProductIdAndStoreId(PRODUCT_ID, STORE_ID);
    ArgumentCaptor<PriceDaily> dailyCaptor = ArgumentCaptor.forClass(PriceDaily.class);
    verify(priceDailyRepository).save(dailyCaptor.capture());
    PriceDaily saved = dailyCaptor.getValue();
    assertThat(saved.getDay()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(saved.getPriceKind()).isEqualTo(PriceKind.REGULAR);
    assertThat(saved.getUnitPrice()).isEqualByComparingTo("12"); // stejný medián jako u price_current
    assertThat(saved.getNObs()).isEqualTo(3);
  }

  @Test
  void dailyRowsAreSplitByDayAndPriceKind() {
    givenQueuedCell();
    OffsetDateTime day1 = OffsetDateTime.parse("2026-07-01T10:00:00Z");
    OffsetDateTime day2 = OffsetDateTime.parse("2026-07-02T10:00:00Z");
    List<PriceObservation> observations = List.of(
        observation(new BigDecimal("10"), SubmitterKind.REGISTERED, PriceKind.REGULAR, day1),
        observation(new BigDecimal("9"), SubmitterKind.REGISTERED, PriceKind.PROMO, day1),
        observation(new BigDecimal("11"), SubmitterKind.REGISTERED, PriceKind.REGULAR, day2));
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(observations);

    service.processQueue();

    ArgumentCaptor<PriceDaily> dailyCaptor = ArgumentCaptor.forClass(PriceDaily.class);
    verify(priceDailyRepository, times(3)).save(dailyCaptor.capture());
    List<PriceDaily> saved = dailyCaptor.getAllValues();
    assertThat(saved).extracting(PriceDaily::getDay)
        .containsExactlyInAnyOrder(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
    assertThat(saved).extracting(PriceDaily::getPriceKind)
        .containsExactlyInAnyOrder(PriceKind.REGULAR, PriceKind.PROMO, PriceKind.REGULAR);
  }

  @Test
  void rejectedObservationsClearExistingDailyRow() {
    givenQueuedCell();
    // Poslední aktivní observace zmizela (zamítnutí/moderace) — recompute dostane prázdný
    // seznam. Denní řádek z předchozího přepočtu musí zmizet, ne zůstat jako duch.
    when(priceObservationRepository.findByProductIdAndStoreIdAndStatus(PRODUCT_ID, STORE_ID, ObservationStatus.ACTIVE))
        .thenReturn(List.of());

    service.processQueue();

    verify(priceDailyRepository).deleteByProductIdAndStoreId(PRODUCT_ID, STORE_ID);
    verify(priceDailyRepository, never()).save(any());
  }
}
