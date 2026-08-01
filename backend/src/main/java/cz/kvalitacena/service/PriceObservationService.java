package cz.kvalitacena.service;

import cz.kvalitacena.controller.SubmitObservationInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Zápis cenového záznamu — viz docs/datovy-model.md (jednotková cena, snapshot gramáže)
 * a docs/reputace.md (anonymní vs. registrovaný submitter). Skutečný přepočet agregátů
 * (vážený medián) dělá PriceAggregationService, tady se jen zapíše observace a zařadí
 * do fronty přepočtu.
 */
@Service
@RequiredArgsConstructor
public class PriceObservationService {

  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final AppUserRepository appUserRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final PriceAggregationService priceAggregationService;
  private final EntityManager entityManager;

  @Transactional
  public PriceObservation submit(SubmitObservationInput input, UUID authenticatedPublicUid,
      ObservationSource source) {
    Product product = productRepository.findById(input.productId())
        .orElseThrow(() -> new NotFoundException("Produkt s tímto id neexistuje"));
    Store store = storeRepository.findById(input.storeId())
        .orElseThrow(() -> new NotFoundException("Obchod s tímto id neexistuje"));

    AppUser submitter = authenticatedPublicUid == null
        ? null
        : appUserRepository.findByPublicUid(authenticatedPublicUid).orElse(null);

    QuantityBasis basis = input.quantityBasis() != null ? input.quantityBasis() : QuantityBasis.PACKAGE;
    PriceKind kind = input.priceKind() != null ? input.priceKind() : PriceKind.REGULAR;

    // Snapshot gramáže/objemu v době zápisu (docs/datovy-model.md) — pozdější oprava produktu
    // nesmí zpětně přepsat jednotkové ceny historie.
    BigDecimal netContentBase = switch (basis) {
      case PER_KG, PER_L -> BigDecimal.ONE; // cena na cedulce je už za kg/l
      case PACKAGE, PER_PIECE -> product.getNetContentBase();
    };

    BigDecimal priceAmount = input.priceAmount();
    if (kind == PriceKind.MULTIBUY) {
      int qty = input.multibuyQty() != null ? input.multibuyQty() : 1;
      netContentBase = netContentBase.multiply(BigDecimal.valueOf(qty));
      if (input.multibuyTotal() != null) {
        priceAmount = input.multibuyTotal();
      }
    }

    PriceObservation observation = PriceObservation.builder()
        .product(product)
        .store(store)
        .priceAmount(priceAmount)
        .priceKind(kind)
        .quantityBasis(basis)
        .multibuyQty(input.multibuyQty())
        .multibuyTotal(input.multibuyTotal())
        .netContentBase(netContentBase)
        .observedAt(input.observedAt() != null ? input.observedAt() : OffsetDateTime.now())
        .submitter(submitter)
        .submitterKind(submitter != null ? SubmitterKind.REGISTERED : SubmitterKind.ANONYMOUS)
        .source(source)
        .build();

    observation = priceObservationRepository.saveAndFlush(observation);
    // unit_price je v DB GENERATED ALWAYS ... STORED. findById by tu nepomohl — entita je už
    // v persistence kontextu (first-level cache), takže by se vrátila beze změny, bez SELECTu.
    // entityManager.refresh() vynutí skutečné znovunačtení z DB (viz PriceObservation).
    entityManager.refresh(observation);

    priceAggregationService.enqueueRecompute(product.getId(), store.getId(), RecomputeReason.NEW_OBS);

    return observation;
  }
}
