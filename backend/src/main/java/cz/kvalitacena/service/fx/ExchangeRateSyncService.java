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
 * Denní stahování kurzovního lístku — ČNB pro drtivou většinu měn, NBS pro RSD (docs/
 * lokalizace.md, "Kurzovní lístek a zobrazovací měna"; plán expanze o 13 dalších zemí). Stejný
 * vzorec jako {@link cz.kvalitacena.service.PriceAggregationService#processQueue} a
 * {@link cz.kvalitacena.security.RefreshTokenService#cleanup} — {@code @EnableScheduling} je už
 * na {@code KvalitaACenaApplication}, žádné ShedLock/Quartz v projektu není. Zápis je
 * idempotentní ({@link #saveNew}), takže případný souběh dvou instancí nic nezkazí.
 *
 * <p>{@link #sources} je {@code List<ExchangeRateSource>}, ne jeden zdroj — stejný vzor jako
 * {@code CompanyIdValidators}/{@code CompanyRegistries} u registrů IČO, aby přidání dalšího
 * zdroje kurzů (kdyby jednou přibyla další měna mimo ČNB i NBS) nebylo zásahem do téhle třídy.
 * Zdroje se dotazují nezávisle a jejich řádky se jen sloučí — jeden zdroj, který neodpoví
 * (výpadek, chybějící NBS klíč), nezablokuje uložení řádků od ostatních.
 *
 * <p>{@link #syncOnStartup} navíc spouští totéž hned po startu (přes {@link ApplicationReadyEvent}),
 * aby čerstvá databáze nečekala na první cron.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateSyncService {

  private final List<ExchangeRateSource> sources;
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
    List<TaggedRow> rows = latest.isEmpty()
        ? backfill(today)
        : catchUp(latest.get().getRateDate(), today);

    int saved = saveNew(rows);
    if (saved > 0) {
      log.info("Kurzovní lístek: uloženo {} nových kurzů (do dne {}).", saved, today);
    }
  }

  /**
   * Prázdná tabulka — dožene historii od nejstarší ceny v {@code core.price_observation}
   * (docs/lokalizace.md), zarovnanou na začátek roku a omezenou {@code app.fx.max-backfill-years}
   * zpátky. Bez jediné ceny v DB appka nemá co dohánět — stáhne jen dnešní lístek.
   */
  private List<TaggedRow> backfill(LocalDate today) {
    Optional<OffsetDateTime> earliest = priceObservationRepository.findEarliestObservedAt();
    if (earliest.isEmpty()) {
      return fetchDayFromAllSources(today);
    }
    LocalDate earliestDay = earliest.get().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    LocalDate cap = today.minusYears(fxProperties.getMaxBackfillYears());
    LocalDate from = (earliestDay.isBefore(cap) ? cap : earliestDay).withDayOfYear(1);
    return fetchRange(from, today);
  }

  /** Neprázdná tabulka — jen chybějící dny od posledního staženého po dnešek. */
  private List<TaggedRow> catchUp(LocalDate lastKnown, LocalDate today) {
    LocalDate from = lastKnown.plusDays(1);
    return from.isAfter(today) ? List.of() : fetchRange(from, today);
  }

  /**
   * Malá mezera → denní endpoint den po dni (ČNB/NBS o víkendu/svátku vrátí poslední předchozí
   * lístek nebo nic, takže duplicity odfiltruje až {@link #saveNew}). Velká mezera → roční
   * endpoint, jeden request místo desítek — jen u zdrojů, které ho mají (NBS zatím nemá,
   * {@link NbsRateSource#fetchYear} vrací prázdno, takže RSD se u velké mezery dožene až dalším
   * catch-upem po dnech).
   */
  private List<TaggedRow> fetchRange(LocalDate from, LocalDate to) {
    List<TaggedRow> rows = new ArrayList<>();
    if (ChronoUnit.DAYS.between(from, to) > 30) {
      for (int year = from.getYear(); year <= to.getYear(); year++) {
        for (ExchangeRateSource src : sources) {
          for (ExchangeRateSource.FxRateRow row : src.fetchYear(year)) {
            rows.add(new TaggedRow(src.name(), row));
          }
        }
      }
    } else {
      for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
        rows.addAll(fetchDayFromAllSources(d));
      }
    }
    return rows.stream().filter(r -> !r.row().validFor().isBefore(from) && !r.row().validFor().isAfter(to)).toList();
  }

  private List<TaggedRow> fetchDayFromAllSources(LocalDate date) {
    List<TaggedRow> rows = new ArrayList<>();
    for (ExchangeRateSource src : sources) {
      for (ExchangeRateSource.FxRateRow row : src.fetchDay(date)) {
        rows.add(new TaggedRow(src.name(), row));
      }
    }
    return rows;
  }

  /** Uloží jen sledované měny (app.fx.tracked-currencies) a jen dny, které ještě nemáme — idempotentní. */
  private int saveNew(List<TaggedRow> rows) {
    int saved = 0;
    for (TaggedRow tagged : rows) {
      ExchangeRateSource.FxRateRow row = tagged.row();
      if (row.amount() <= 0 || !fxProperties.getTrackedCurrencies().contains(row.currencyCode())) continue;
      ExchangeRateId id = new ExchangeRateId(row.validFor(), row.currencyCode());
      if (exchangeRateRepository.existsById(id)) continue;

      BigDecimal czkPerUnit = row.rate().divide(BigDecimal.valueOf(row.amount()), 6, RoundingMode.HALF_UP);
      exchangeRateRepository.save(ExchangeRate.builder()
          .rateDate(row.validFor())
          .currency(row.currencyCode())
          .czkPerUnit(czkPerUnit)
          .source(tagged.sourceName())
          .fetchedAt(OffsetDateTime.now())
          .build());
      saved++;
    }
    return saved;
  }

  /** Řádek spárovaný s tím, který {@link ExchangeRateSource} ho vrátil — jde do {@code fx.exchange_rate.source}. */
  private record TaggedRow(String sourceName, ExchangeRateSource.FxRateRow row) {
  }
}
