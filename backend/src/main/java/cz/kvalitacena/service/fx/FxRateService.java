package cz.kvalitacena.service.fx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cz.kvalitacena.config.FxProperties;
import cz.kvalitacena.db.repo.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Přepočet mezi měnami kurzem ČNB platným k danému dni (docs/lokalizace.md, "Kurzovní lístek a
 * zobrazovací měna"). CZK je pivot — křížový kurz jde vždy přes ni ({@code from → CZK → to}),
 * stejně jako v {@code fx.exchange_rate} (žádný přímý EUR/PLN kurz se neukládá ani nepočítá
 * zvlášť).
 *
 * <p>Kterým dnem se přepočítává je vždy na volajícím (PriceHistoryService pro každý bod grafu
 * jeho vlastním dnem, jinde {@code observedAt}/{@code lastObservedAt}) — NIKDY dnešním kurzem,
 * jinak by graf vývoje ceny v USD mísil pohyb ceny s pohybem kurzu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRateService {

  private static final MathContext MC = MathContext.DECIMAL64;
  private static final int RESULT_SCALE = 4;

  private final ExchangeRateRepository exchangeRateRepository;
  private final FxProperties fxProperties;

  // Líný vzorec jako GeocodingService — @ConfigurationProperties binding vs. @PostConstruct
  // pořadí není zaručené. Cachuje se i "kurz neznáme" (Optional.empty) — bez toho by chybějící
  // den zbytečně bušil do DB při každém vykreslení grafu.
  private volatile Cache<CacheKey, Optional<RateAt>> cache;

  private synchronized Cache<CacheKey, Optional<RateAt>> cache() {
    if (cache == null) {
      cache = Caffeine.newBuilder()
          .expireAfterWrite(fxProperties.getCacheTtl())
          .maximumSize(5000)
          .build();
    }
    return cache;
  }

  private Optional<RateAt> rateAt(String currency, LocalDate at) {
    if (currency == null || at == null) return Optional.empty();
    return cache().get(new CacheKey(currency, at), key -> exchangeRateRepository
        .findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(key.currency(), key.at())
        .map(rate -> new RateAt(rate.getCzkPerUnit(), rate.getRateDate())));
  }

  /** Kolik CZK stojí 1 jednotka měny k danému dni (poslední publikovaný lístek k datu nebo dřívější); empty = kurz neznáme. */
  @Transactional(readOnly = true)
  public Optional<BigDecimal> czkPerUnit(String currency, LocalDate at) {
    if ("CZK".equals(currency)) return Optional.of(BigDecimal.ONE);
    return rateAt(currency, at).map(RateAt::czkPerUnit);
  }

  /**
   * Přepočet částky {@code from} → {@code to} kurzem platným k {@code at}. Empty vždy, když se
   * nic nepřepočítalo — stejná měna, chybějící kurz — klient pak ukáže originál.
   */
  @Transactional(readOnly = true)
  public Optional<Converted> convert(BigDecimal amount, String from, String to, LocalDate at) {
    if (amount == null || from == null || to == null || at == null || from.equals(to)) {
      return Optional.empty();
    }

    Optional<BigDecimal> fromCzk = "CZK".equals(from) ? Optional.of(BigDecimal.ONE) : rateAt(from, at).map(RateAt::czkPerUnit);
    Optional<BigDecimal> toCzk = "CZK".equals(to) ? Optional.of(BigDecimal.ONE) : rateAt(to, at).map(RateAt::czkPerUnit);
    if (fromCzk.isEmpty() || toCzk.isEmpty()) {
      log.debug("Kurz {}/{} k {} není známý, přepočet se přeskakuje.", from, to, at);
      return Optional.empty();
    }

    // rateDate = nejmladší ze dvou skutečně použitých lístků (u páru zahrnujícího CZK je to
    // prostě datum toho druhého) — ať UI vždy popíše přepočet tím čerstvějším ze dvou kurzů.
    LocalDate fromDate = "CZK".equals(from) ? at : rateAt(from, at).map(RateAt::rateDate).orElse(at);
    LocalDate toDate = "CZK".equals(to) ? at : rateAt(to, at).map(RateAt::rateDate).orElse(at);
    LocalDate rateDate = fromDate.isAfter(toDate) ? fromDate : toDate;

    BigDecimal converted = amount.multiply(fromCzk.get(), MC)
        .divide(toCzk.get(), RESULT_SCALE, RoundingMode.HALF_UP);
    return Optional.of(new Converted(converted, to, rateDate));
  }

  public record Converted(BigDecimal amount, String currency, LocalDate rateDate) {
  }

  private record CacheKey(String currency, LocalDate at) {
  }

  private record RateAt(BigDecimal czkPerUnit, LocalDate rateDate) {
  }
}
