package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.SubmitObservationsInput;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Zápis dávky cenových záznamů z JEDNÉ cenovky — viz docs/datovy-model.md (jednotková cena,
 * snapshot gramáže) a docs/reputace.md (anonymní vs. registrovaný submitter, unikátnost podle
 * druhu ceny). U regálu bývá cena běžná i cena s věrnostní kartou, případně množstevní sleva;
 * {@link #submit} je zapíše jedním voláním v jedné transakci — kolize jediného druhu ceny
 * s dnešním zápisem shodí celou dávku, žádná ceny se neuloží částečně. Skutečný přepočet
 * agregátů (vážený medián) dělá PriceAggregationService, tady se jen zapíšou observace
 * a zařadí do fronty přepočtu.
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
  private final ProductOverlayService productOverlayService;
  private final StoreService storeService;
  private final CurrencyResolver currencyResolver;
  private final ProductScopeService productScopeService;
  private final ProductAliasService productAliasService;
  private final EntityManager entityManager;

  @Transactional
  public List<PriceObservation> submit(SubmitObservationsInput input, UUID authenticatedPublicUid,
      ObservationSource source) {
    List<ObservationPriceInput> prices = input.prices();
    if (prices == null || prices.isEmpty()) {
      throw new ValidationException(ErrorCode.OBSERVATION_PRICES_REQUIRED);
    }

    // Duplicita druhu ceny UVNITŘ dávky — v pořadí, ve kterém je uživatel vidí ve formuláři
    // (LinkedHashSet), aby hláška jmenovala řádek, který vidí jako druhý, ne pořadí enumu.
    // Kontrola proběhne dřív, než padne jediný dotaz do DB.
    Set<PriceKind> kindsInBatch = new LinkedHashSet<>();
    for (ObservationPriceInput price : prices) {
      PriceKind kind = price.priceKind() != null ? price.priceKind() : PriceKind.REGULAR;
      if (!kindsInBatch.add(kind)) {
        throw new ValidationException(ErrorCode.OBSERVATION_DUPLICATE_PRICE_KIND, kind.name());
      }
      validateShape(kind, price);
      validatePromoValidity(kind, price);
    }

    Product product = productRepository.findById(input.productId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    Store store = storeRepository.findById(input.storeId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
    if (!productScopeService.isAvailableAt(product, store)) {
      throw new ValidationException(ErrorCode.PRODUCT_NOT_AVAILABLE_AT_STORE);
    }

    AppUser submitter = authenticatedPublicUid == null
        ? null
        : appUserRepository.findByPublicUid(authenticatedPublicUid).orElse(null);
    Product effectiveProduct = productOverlayService.applyOverlay(product, submitter == null ? null : submitter.getId());

    // Hlavička dávky — vyhodnocená JEDNOU pro celou cenovku, ne per řádek (dvě ceny z jednoho
    // regálu by jinak mohly spadnout do různých dnů UTC).
    QuantityBasis basis = input.quantityBasis() != null ? input.quantityBasis() : QuantityBasis.PACKAGE;
    OffsetDateTime observedAt = input.observedAt() != null ? input.observedAt() : OffsetDateTime.now();
    // Měna je vlastnost PROVOZOVNY, ne přání zapisujícího (docs/lokalizace.md) — input.currency
    // je jen výjimka pro příhraniční prodejny, které cení v jiné měně než země obchodu, a musí
    // projít whitelistem (currencyResolver.isSupported), jinak by neplatná hodnota od klienta
    // proklouzla rovnou do agregátu.
    String currency = input.currency() != null && currencyResolver.isSupported(input.currency())
        ? input.currency()
        : currencyResolver.forStore(store);
    // Snapshot gramáže/objemu v době zápisu (docs/datovy-model.md) — pozdější oprava produktu
    // nesmí zpětně přepsat jednotkové ceny historie.
    BigDecimal baseNetContent = switch (basis) {
      case PER_KG, PER_L -> BigDecimal.ONE; // cena na cedulce je už za kg/l
      case PACKAGE, PER_PIECE -> effectiveProduct.getNetContentBase();
    };

    // Pre-kontrola kolize s DB — jen pro registrované (index je parciální, WHERE submitter_id
    // IS NOT NULL). Nic zatím není zapsáno, takže "celá dávka selže" vychází zadarmo.
    if (submitter != null) {
      List<String> alreadySubmittedToday = priceObservationRepository.findPriceKindsBySubmitterOnDay(
          product.getId(), store.getId(), submitter.getId(), observedAt);
      Set<String> alreadySubmittedTodaySet = Set.copyOf(alreadySubmittedToday);
      for (PriceKind kind : kindsInBatch) {
        if (alreadySubmittedTodaySet.contains(kind.name())) {
          throw new ValidationException(ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY, kind.name());
        }
      }
    }

    List<PriceObservation> toSave = new ArrayList<>(prices.size());
    for (ObservationPriceInput price : prices) {
      PriceKind kind = price.priceKind() != null ? price.priceKind() : PriceKind.REGULAR;
      BigDecimal netContentBase = baseNetContent;
      BigDecimal priceAmount = price.priceAmount();
      if (kind == PriceKind.MULTIBUY) {
        netContentBase = netContentBase.multiply(BigDecimal.valueOf(price.multibuyQty()));
        priceAmount = price.multibuyTotal();
      }

      toSave.add(PriceObservation.builder()
          .product(product)
          .store(store)
          .priceAmount(priceAmount)
          .currency(currency)
          .priceKind(kind)
          .quantityBasis(basis)
          .multibuyQty(price.multibuyQty())
          .multibuyTotal(price.multibuyTotal())
          .netContentBase(netContentBase)
          .promoValidFrom(price.promoValidFrom())
          .promoValidTo(price.promoValidTo())
          .observedAt(observedAt)
          .submitter(submitter)
          .submitterKind(submitter != null ? SubmitterKind.REGISTERED : SubmitterKind.ANONYMOUS)
          .source(source)
          .build());
    }

    // uq_price_observation_submitter_kind_per_day (1 záznam/uživatel/produkt/obchod/druh ceny/
    // den — hrubá síla na spamování nefunguje, docs/reputace.md) by jinak spadla jako nepřeloženou
    // DataIntegrityViolationException až do GraphQL/REST vrstvy (INTERNAL_ERROR bez detailu).
    // Tohle je fallback pro závod dvou souběžných requestů — předem zjistitelnou kolizi odchytí
    // už pre-kontrola výš, s konkrétním druhem ceny v hlášce.
    List<PriceObservation> saved;
    try {
      saved = priceObservationRepository.saveAllAndFlush(toSave);
    } catch (DataIntegrityViolationException e) {
      throw new ValidationException(ErrorCode.OBSERVATION_ALREADY_SUBMITTED_TODAY, e);
    }
    // unit_price je v DB GENERATED ALWAYS ... STORED. findById by tu nepomohl — entity jsou už
    // v persistence kontextu (first-level cache), takže by se vrátily beze změny, bez SELECTu.
    // entityManager.refresh() vynutí skutečné znovunačtení z DB (viz PriceObservation).
    saved.forEach(entityManager::refresh);

    // Čítač pro TrustLevelService — +1 za DÁVKU, ne za řádek. Jedna návštěva jednoho regálu je
    // jeden bod bez ohledu na to, kolik cen z něj uživatel opsal; jinak by práh T2 (min-
    // observations = 5) šlo naplnit jediným odesláním u jedné cenovky. NEpočítá se zpětně
    // z historie observací, protože submitter_id se po 180 dnech nuluje (docs/soukromi.md).
    if (submitter != null) {
      submitter.setObservationCount(submitter.getObservationCount() + 1);
      appUserRepository.save(submitter);
      productAliasService.confirmFromObservation(product, submitter, input.productAlias());
    }

    // Jedna položka fronty pro celou dávku — agg.recompute_queue zná jen (product_id, store_id),
    // rozpad na (price_kind, currency) dělá až PriceAggregationService.recomputeCell.
    priceAggregationService.enqueueRecompute(product.getId(), store.getId(), RecomputeReason.NEW_OBS);

    // Bezkódová (DRAFT) položka / provozovna od nedůvěryhodného autora (PENDING) se překlopí
    // na ACTIVE, jakmile ji potvrdí dost různých přispěvatelů — viz docs/reputace.md. Jedno
    // volání na dávku, ne na řádek.
    if (product.getStatus() == ProductStatus.DRAFT) {
      productCatalogService.promoteIfConfirmed(product.getId());
    }
    if (store.getStatus() == StoreStatus.PENDING) {
      storeService.promoteIfConfirmed(store.getId());
    }

    return saved;
  }

  private static void validateShape(PriceKind kind, ObservationPriceInput price) {
    boolean incomplete = kind == PriceKind.MULTIBUY
        ? price.multibuyQty() == null || price.multibuyQty() < 2 || price.multibuyTotal() == null
        : price.priceAmount() == null;
    if (incomplete) {
      throw new ValidationException(ErrorCode.OBSERVATION_PRICE_INCOMPLETE, kind.name());
    }
  }

  /**
   * Platnost akce (docs/datovy-model.md) — obě pole nepovinná, smí se vyplnit jen u PROMO
   * (u ostatních druhů cena žádnou platnost nemá). {@code promoValidFrom} nesmí být v
   * budoucnu: zapisuje se cena, kterou uživatel VIDĚL v regále, ne cena z letáku, která ještě
   * nezačala platit (ta zůstává mimo tenhle model, docs/rozvoj.md).
   */
  private static void validatePromoValidity(PriceKind kind, ObservationPriceInput price) {
    if (price.promoValidFrom() == null && price.promoValidTo() == null) {
      return;
    }
    if (kind != PriceKind.PROMO) {
      throw new ValidationException(ErrorCode.OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED, kind.name());
    }
    if (price.promoValidFrom() != null && price.promoValidTo() != null
        && price.promoValidFrom().isAfter(price.promoValidTo())) {
      throw new ValidationException(ErrorCode.OBSERVATION_PROMO_VALIDITY_RANGE_INVALID);
    }
    if (price.promoValidFrom() != null && price.promoValidFrom().isAfter(LocalDate.now())) {
      throw new ValidationException(ErrorCode.OBSERVATION_PROMO_VALID_FROM_IN_FUTURE);
    }
  }
}
