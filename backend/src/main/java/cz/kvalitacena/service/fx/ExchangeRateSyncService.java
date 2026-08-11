package cz.kvalitacena.service.fx;

import cz.kvalitacena.config.FxProperties;
import cz.kvalitacena.db.entity.ExchangeRate;
import cz.kvalitacena.db.entity.ExchangeRateId;
import cz.kvalitacena.db.repo.ExchangeRateRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Denní stahování kurzovního lístku ČNB (docs/lokalizace.md, "Kurzovní lístek a zobrazovací
 * měna"). Stejný vzorec jako {@link cz.kvalitacena.service.PriceAggregationService#processQueue}
 * a {@link cz.kvalitacena.security.RefreshTokenService#cleanup} — {@code @EnableScheduling} je
 * už na {@code KvalitaACenaApplication}, žádné ShedLock/Quartz v projektu není. Zápis je
 * idempotentní ({@link #saveNew}), takže případný souběh dvou instancí nic nezkazí.
 *
 * <p>{@link #syncOnStartup} navíc spouští totéž hned po startu (přes {@link ApplicationReadyEvent}),
 * aby čerstvá databáze nečekala na první cron.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateSyncService {

  private final ExchangeRateSource source;
  private final ExchangeRateRepository exchangeRateRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final FxProperties fxProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void syncOnStartup() {
    sync();
  }

  @Scheduled(cron = "${app.fx.cron}", zone = "${app.fx.zone}")
  public void syncDaily() {
    sync();
  }

  @Transactional
  public void sync() {
    if (!fxProperties.isEnabled()) return;
    LocalDate today = LocalDate.now(ZoneId.of(fxProperties.getZone()));

    Optional<ExchangeRate> latest = exchangeRateRepository.findTopByOrderByRateDateDesc();
    List<ExchangeRateSource.FxRateRow> rows = latest.isEmpty()
        ? backfill(today)
        : catchUp(latest.get().getRateDate(), today);

    int saved = saveNew(rows);
    if (saved > 0) {
      log.info("Kurzovní lístek ČNB: uloženo {} nových kurzů (do dne {}).", saved, today);
    }
  }

  /**
   * Prázdná tabulka — dožene historii od nejstarší ceny v {@code core.price_observation}
   * (docs/lokalizace.md), zarovnanou na začátek roku a omezenou {@code app.fx.max-backfill-years}
   * zpátky. Bez jediné ceny v DB appka nemá co dohánět — stáhne jen dnešní lístek.
   */
  private List<ExchangeRateSource.FxRateRow> backfill(LocalDate today) {
    Optional<OffsetDateTime> earliest = priceObservationRepository.findEarliestObservedAt();
    if (earliest.isEmpty()) {
      return source.fetchDay(today);
    }
    LocalDate earliestDay = earliest.get().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    LocalDate cap = today.minusYears(fxProperties.getMaxBackfillYears());
    LocalDate from = (earliestDay.isBefore(cap) ? cap : earliestDay).withDayOfYear(1);
    return fetchRange(from, today);
  }

  /** Neprázdná tabulka — jen chybějící dny od posledního staženého po dnešek. */
  private List<ExchangeRateSource.FxRateRow> catchUp(LocalDate lastKnown, LocalDate today) {
    LocalDate from = lastKnown.plusDays(1);
    return from.isAfter(today) ? List.of() : fetchRange(from, today);
  }

  /**
   * Malá mezera → denní endpoint den po dni (ČNB o víkendu/svátku vrátí poslední předchozí
   * lístek, takže duplicity odfiltruje až {@link #saveNew}). Velká mezera → roční endpoint,
   * jeden request místo desítek.
   */
  private List<ExchangeRateSource.FxRateRow> fetchRange(LocalDate from, LocalDate to) {
    List<ExchangeRateSource.FxRateRow> rows = new ArrayList<>();
    if (ChronoUnit.DAYS.between(from, to) > 30) {
      for (int year = from.getYear(); year <= to.getYear(); year++) {
        rows.addAll(source.fetchYear(year));
      }
    } else {
      for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
        rows.addAll(source.fetchDay(d));
      }
    }
    return rows.stream().filter(r -> !r.validFor().isBefore(from) && !r.validFor().isAfter(to)).toList();
  }

  /** Uloží jen sledované měny (app.fx.tracked-currencies) a jen dny, které ještě nemáme — idempotentní. */
  private int saveNew(List<ExchangeRateSource.FxRateRow> rows) {
    int saved = 0;
    for (ExchangeRateSource.FxRateRow row : rows) {
      if (row.amount() <= 0 || !fxProperties.getTrackedCurrencies().contains(row.currencyCode())) continue;
      ExchangeRateId id = new ExchangeRateId(row.validFor(), row.currencyCode());
      if (exchangeRateRepository.existsById(id)) continue;

      BigDecimal czkPerUnit = row.rate().divide(BigDecimal.valueOf(row.amount()), 6, RoundingMode.HALF_UP);
      exchangeRateRepository.save(ExchangeRate.builder()
          .rateDate(row.validFor())
          .currency(row.currencyCode())
          .czkPerUnit(czkPerUnit)
          .fetchedAt(OffsetDateTime.now())
          .build());
      saved++;
    }
    return saved;
  }
}
