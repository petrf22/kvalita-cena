package cz.kvalitacena.service;

import cz.kvalitacena.controller.MyPrice;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "Vaše cena" — poslední vlastní zápis přihlášeného uživatele na (produkt, obchod, druh ceny),
 * zobrazený vedle komunitního agregátu. Dávkové dotažení pro {@code Product.myPrices}
 * (BatchMapping), stejný vzor jako {@link ProductOverlayService}.
 */
@Service
@RequiredArgsConstructor
public class MyPriceService {

  private final PriceObservationRepository priceObservationRepository;

  @Transactional(readOnly = true)
  public Map<Long, List<MyPrice>> myPricesByProductId(Collection<Long> productIds, Long viewerId) {
    if (viewerId == null || productIds.isEmpty()) return Map.of();
    return priceObservationRepository.findLatestOwnByProductIdIn(viewerId, productIds).stream()
        .collect(Collectors.groupingBy(o -> o.getProduct().getId(),
            Collectors.mapping(this::toMyPrice, Collectors.toList())));
  }

  private MyPrice toMyPrice(PriceObservation observation) {
    return new MyPrice(observation.getStore(), observation.getPriceKind(), observation.getPriceAmount(),
        observation.getUnitPrice(), observation.getObservedAt());
  }
}
