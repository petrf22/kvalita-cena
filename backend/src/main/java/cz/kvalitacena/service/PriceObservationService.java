package cz.kvalitacena.service;

import cz.kvalitacena.controller.SubmitObservationInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final ProductCatalogService productCatalogService;
  private final StoreService storeService;
  private final CurrencyResolver currencyResolver;
  private final EntityManager entityManager;

  @Transactional
  public PriceObservation submit(SubmitObservationInput input, UUID authenticatedPublicUid,
      ObservationSource source) {
    Product product = productRepository.findById(input.productId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    Store store = storeRepository.findById(input.storeId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));

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

    // Měna je vlastnost PROVOZOVNY, ne přání zapisujícího (docs/lokalizace.md) — input.currency
    // je jen výjimka pro příhraniční prodejny, které cení v jiné měně než země obchodu, a musí
    // projít whitelistem (currencyResolver.isSupported), jinak by neplatná hodnota od klienta
    // proklouzla rovnou do agregátu.
    String currency = input.currency() != null && currencyResolver.isSupported(input.currency())
        ? input.currency()
        : currencyResolver.forStore(store);

    PriceObservation observation = PriceObservation.builder()
        .product(product)
        .store(store)
        .priceAmount(priceAmount)
        .currency(currency)
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

    // uq_price_observation_submitter_per_day (1 záznam/uživatel/produkt/obchod/den — hrubá síla
    // na spamování nefunguje, docs/reputace.md) by jinak spadla jako nepřeložená
    // DataIntegrityViolationException až do GraphQL/REST vrstvy (INTERNAL_ERROR bez detailu).
    try {
      observation = priceObservationRepository.saveAndFlush(observation);
    } catch (DataIntegrityViolationException e) {
      throw new ValidationException(ErrorCode.OBSERVATION_ALREADY_SUBMITTED_TODAY, e);
    }
    // unit_price je v DB GENERATED ALWAYS ... STORED. findById by tu nepomohl — entita je už
    // v persistence kontextu (first-level cache), takže by se vrátila beze změny, bez SELECTu.
    // entityManager.refresh() vynutí skutečné znovunačtení z DB (viz PriceObservation).
    entityManager.refresh(observation);

    // Čítač pro TrustLevelService — NEpočítá se zpětně z historie observací, protože
    // submitter_id se po 180 dnech nuluje (docs/soukromi.md).
    if (submitter != null) {
      submitter.setObservationCount(submitter.getObservationCount() + 1);
      appUserRepository.save(submitter);
    }

    priceAggregationService.enqueueRecompute(product.getId(), store.getId(), RecomputeReason.NEW_OBS);

    // Bezkódová (DRAFT) položka / provozovna od nedůvěryhodného autora (PENDING) se překlopí
    // na ACTIVE, jakmile ji potvrdí dost různých přispěvatelů — viz docs/reputace.md.
    if (product.getStatus() == ProductStatus.DRAFT) {
      productCatalogService.promoteIfConfirmed(product.getId());
    }
    if (store.getStatus() == StoreStatus.PENDING) {
      storeService.promoteIfConfirmed(store.getId());
    }

    return observation;
  }
}
