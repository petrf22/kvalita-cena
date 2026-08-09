package cz.kvalitacena.service;

import cz.kvalitacena.config.PriceHistoryProperties;
import cz.kvalitacena.controller.PriceHistory;
import cz.kvalitacena.controller.PricePoint;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.PriceDailyRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Denní řada z agg.price_daily pro graf vývoje ceny — NIKDY ze syrových core.price_observation
 * (docs/datovy-model.md). Bez storeId je to medián mediánů přes provozovny (docs/reputace.md).
 * {@link PriceHistory#currency} je VŽDY vyplněná (docs/lokalizace.md) — graf tím vždy ví, čím
 * popsat osu, a nikdy nesmíchá dvě měnové řady.
 */
@Service
@RequiredArgsConstructor
public class PriceHistoryService {

  private final PriceDailyRepository priceDailyRepository;
  private final StoreRepository storeRepository;
  private final PriceHistoryProperties properties;
  private final CurrencyResolver currencyResolver;

  @Transactional(readOnly = true)
  public PriceHistory history(Long productId, PriceKind priceKind, Long storeId, Integer requestedDays,
      String requestedCurrency, boolean authenticated) {
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
      List<PricePoint> points = priceDailyRepository
          .findByProductIdAndStoreIdAndPriceKindAndCurrencyAndDayGreaterThanEqualOrderByDayAsc(
              productId, storeId, kind, currency, fromDay)
          .stream()
          .map(d -> new PricePoint(d.getDay(), d.getPriceAmount(), d.getUnitPrice(), d.getNObs(), 1))
          .toList();
      return new PriceHistory(kind, store, days, currency, points);
    }

    String currency = requestedCurrency != null && currencyResolver.isSupported(requestedCurrency)
        ? requestedCurrency
        : priceDailyRepository.dominantCurrency(productId, kind.name(), fromDay)
            .orElseGet(currencyResolver::defaultCurrency);

    List<PricePoint> points = priceDailyRepository.nationalHistory(productId, kind.name(), currency, fromDay).stream()
        .map(row -> new PricePoint(row.getDay(), row.getPriceAmount(), row.getUnitPrice(), row.getNObs(), row.getStoreCount()))
        .toList();
    return new PriceHistory(kind, null, days, currency, points);
  }
}
