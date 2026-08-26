package cz.kvalitacena.service;

import cz.kvalitacena.controller.ConvertedPrice;
import cz.kvalitacena.controller.MyPrice;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final FxRateService fxRateService;

  @Transactional(readOnly = true)
  public Map<Long, List<MyPrice>> myPricesByProductId(Collection<Long> productIds, Long viewerId,
      String displayCurrency) {
    if (viewerId == null || productIds.isEmpty()) return Map.of();
    return priceObservationRepository.findLatestOwnByProductIdIn(viewerId, productIds).stream()
        .collect(Collectors.groupingBy(o -> o.getProduct().getId(),
            Collectors.mapping(o -> toMyPrice(o, displayCurrency), Collectors.toList())));
  }

  private MyPrice toMyPrice(PriceObservation observation, String displayCurrency) {
    // Kurz platný K OBSERVED_AT, ne dnešní — jinak by "Vaše cena" v USD skákala se zpožděným
    // starým zápisem podle dnešního kurzu, ne podle kurzu dne, kdy uživatel cenu viděl.
    ConvertedPrice converted = ConvertedPrice.from(displayCurrency == null ? Optional.empty()
        : fxRateService.convert(observation.getPriceAmount(), observation.getCurrency(), displayCurrency,
            observation.getObservedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate()));
    return new MyPrice(observation.getStore(), observation.getPriceKind(), observation.getPriceAmount(),
        observation.getUnitPrice(), observation.getObservedAt(), observation.getCurrency(), converted,
        observation.getPromoValidFrom(), observation.getPromoValidTo());
  }
}
