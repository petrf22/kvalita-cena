package cz.kvalitacena.service;

import cz.kvalitacena.config.PriceHistoryProperties;
import cz.kvalitacena.controller.PriceHistory;
import cz.kvalitacena.controller.PricePoint;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.PriceDailyRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Denní řada z agg.price_daily pro graf vývoje ceny — NIKDY ze syrových core.price_observation
 * (docs/datovy-model.md). Bez storeId je to medián mediánů přes provozovny (docs/reputace.md).
 * {@link PriceHistory#currency} je VŽDY vyplněná (docs/lokalizace.md) — graf tím vždy ví, čím
 * popsat osu, a nikdy nesmíchá dvě měnové řady.
 *
 * <p>{@code displayCurrency} (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") je
 * druhá, nezávislá věc — přepočet KAŽDÉHO bodu jeho VLASTNÍM denním kurzem, nikdy dnešním,
 * jinak by graf v USD mísil pohyb ceny s pohybem kurzu.
 */
@Service
@RequiredArgsConstructor
public class PriceHistoryService {

  private final PriceDailyRepository priceDailyRepository;
  private final StoreRepository storeRepository;
  private final PriceHistoryProperties properties;
  private final CurrencyResolver currencyResolver;
  private final FxRateService fxRateService;
  private final Messages messages;

  @Transactional(readOnly = true)
  public PriceHistory history(Long productId, PriceKind priceKind, Long storeId, Integer requestedDays,
      String requestedCurrency, boolean authenticated, String displayCurrency) {
    int cap = authenticated ? properties.getMaxDays() : properties.getAnonymousMaxDays();
    int requested = requestedDays == null ? 90 : requestedDays;
    int days = Math.max(1, Math.min(requested, cap));
    LocalDate fromDay = LocalDate.now(ZoneOffset.UTC).minusDays(days);
    PriceKind kind = priceKind == null ? PriceKind.REGULAR : priceKind;

    if (storeId != null) {
      Store store = storeRepository.findById(storeId).orElse(null);
      // Volitelný override (requestedCurrency) na store-scoped historii nedává moc smysl —
      // provozovna má svou měnu — ale je to konzistentní s priceHistory(currency) argumentem.
      String currency = requestedCurrency != null && currencyResolver.isSupported(requestedCurrency)
          ? requestedCurrency
          : store != null ? currencyResolver.forStore(store) : currencyResolver.defaultCurrency();
      String effectiveDisplay = effectiveDisplayCurrency(displayCurrency, currency);
      List<PricePoint> points = priceDailyRepository
          .findByProductIdAndStoreIdAndPriceKindAndCurrencyAndDayGreaterThanEqualOrderByDayAsc(
              productId, storeId, kind, currency, fromDay)
          .stream()
          .map(d -> toPoint(d.getDay(), d.getPriceAmount(), d.getUnitPrice(), d.getNObs(), 1, currency, effectiveDisplay))
          .toList();
      return new PriceHistory(kind, store, days, currency, effectiveDisplay, attributionOrNull(effectiveDisplay), points);
    }

    String currency = requestedCurrency != null && currencyResolver.isSupported(requestedCurrency)
        ? requestedCurrency
        : priceDailyRepository.dominantCurrency(productId, kind.name(), fromDay)
            .orElseGet(currencyResolver::defaultCurrency);
    String effectiveDisplay = effectiveDisplayCurrency(displayCurrency, currency);

    List<PricePoint> points = priceDailyRepository.nationalHistory(productId, kind.name(), currency, fromDay).stream()
        .map(row -> toPoint(row.getDay(), row.getPriceAmount(), row.getUnitPrice(), row.getNObs(), row.getStoreCount(),
            currency, effectiveDisplay))
        .toList();
    return new PriceHistory(kind, null, days, currency, effectiveDisplay, attributionOrNull(effectiveDisplay), points);
  }

  /** null, když se nemá přepočítávat — chybějící hlavička, nebo se rovná měně řady (nic k převodu). */
  private String effectiveDisplayCurrency(String displayCurrency, String currency) {
    return displayCurrency == null || displayCurrency.equals(currency) ? null : displayCurrency;
  }

  private String attributionOrNull(String effectiveDisplay) {
    return effectiveDisplay == null ? null : messages.get("attribution.cnb");
  }

  private PricePoint toPoint(LocalDate day, BigDecimal priceAmount, BigDecimal unitPrice, int nObs, int storeCount,
      String currency, String displayCurrency) {
    if (displayCurrency == null) {
      return new PricePoint(day, priceAmount, unitPrice, nObs, storeCount, null, null);
    }
    // Kurz PLATNÝ K "day" — viz třídní javadoc, jádro celého požadavku.
    BigDecimal convertedUnit = fxRateService.convert(unitPrice, currency, displayCurrency, day)
        .map(FxRateService.Converted::amount).orElse(null);
    BigDecimal convertedAmount = priceAmount == null ? null
        : fxRateService.convert(priceAmount, currency, displayCurrency, day)
            .map(FxRateService.Converted::amount).orElse(null);
    return new PricePoint(day, priceAmount, unitPrice, nObs, storeCount, convertedUnit, convertedAmount);
  }
}
