package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code PriceCurrent} nemá JPA vztah na {@code Store} (jen {@code storeId}, viz PriceCurrent —
 * složený klíč přes @IdClass), proto je potřeba explicitní resolver místo výchozího
 * PropertyDataFetcheru. Dávkově (@BatchMapping), ne po jednom — s hledáním, které vrací víc
 * produktů s víc cenami, by @SchemaMapping po jednom byl N+1.
 */
@Controller
@RequiredArgsConstructor
public class PriceCurrentGraphQlController {

  private final StoreRepository storeRepository;
  private final FxRateService fxRateService;

  @BatchMapping(typeName = "PriceCurrent", field = "store")
  public Map<PriceCurrent, Store> store(List<PriceCurrent> priceCurrents) {
    Map<Long, Store> storesById = storeRepository
        .findAllById(priceCurrents.stream().map(PriceCurrent::getStoreId).distinct().toList())
        .stream()
        .collect(Collectors.toMap(Store::getId, Function.identity()));

    Map<PriceCurrent, Store> result = new LinkedHashMap<>();
    for (PriceCurrent pc : priceCurrents) {
      result.put(pc, storesById.get(pc.getStoreId()));
    }
    return result;
  }

  /** Kurz k lastObservedAt, ne dnešní (docs/lokalizace.md) — stejné pravidlo napříč celým API. */
  @BatchMapping(typeName = "PriceCurrent", field = "converted")
  public Map<PriceCurrent, ConvertedPrice> converted(List<PriceCurrent> priceCurrents,
      @ContextValue(name = "displayCurrency", required = false) String displayCurrency) {
    Map<PriceCurrent, ConvertedPrice> result = new LinkedHashMap<>();
    for (PriceCurrent pc : priceCurrents) {
      LocalDate rateAt = pc.getLastObservedAt() == null ? null
          : pc.getLastObservedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
      result.put(pc, displayCurrency == null || rateAt == null ? null
          : ConvertedPrice.from(fxRateService.convert(pc.getUnitPrice(), pc.getCurrency(), displayCurrency, rateAt)));
    }
    return result;
  }
}
