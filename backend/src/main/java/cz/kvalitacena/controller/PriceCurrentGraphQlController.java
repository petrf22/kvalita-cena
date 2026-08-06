package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

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
}
