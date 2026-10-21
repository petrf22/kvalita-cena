package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.SubmitObservationsInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.EvidenceKind;
import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.QuantityBasis;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.entity.ReceiptReviewStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.AppException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Párování řádků účtenky a potvrzení cen (docs/datovy-model.md, „Účtenky"). Tady — a jen tady —
 * z účtenky vzniká {@code core.price_observation}: na pokyn vlastníka, nikdy strojem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptReviewService {

  /** Pod kolizí denního limitu druhu ceny (uq_price_observation_submitter_kind_per_day). */
  public static final String SKIP_DUPLICATE = "DUPLICATE_SAME_DAY";

  private static final Set<ErrorCode> DUPLICATE_CODES = Set.of(
      ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY, ErrorCode.OBSERVATION_ALREADY_SUBMITTED_TODAY);

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository lineRepository;
  private final StoreRepository storeRepository;
  private final ProductRepository productRepository;
  private final AppUserRepository appUserRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final ReceiptMatchingService matchingService;
  private final PriceObservationService priceObservationService;
  private final ProductStoreLabelService labelService;
  private final PlatformTransactionManager transactionManager;

  public record ConfirmResult(Receipt receipt, int confirmed, List<ReceiptLine> skipped) {
  }

  @Transactional
  public Receipt updateHeader(UUID ownerPublicUid, Long receiptId, Long storeId, OffsetDateTime purchasedAt) {
    AppUser owner = requireUser(ownerPublicUid);
    Receipt receipt = ownedReceipt(receiptId, owner);
    if (purchasedAt != null) {
      if (purchasedAt.isAfter(OffsetDateTime.now().plusHours(1))) {
        throw new ValidationException(ErrorCode.RECEIPT_PURCHASE_TIME_IN_FUTURE);
      }
      receipt.setPurchasedAt(purchasedAt);
    }
    if (storeId != null && !storeId.equals(receipt.getStoreId())) {
      Store store = storeRepository.findById(storeId)
          .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
      receipt.setStoreId(store.getId());
      // Jiný obchod = jiný rozsah označení i vnitroobchodních kódů — strojové návrhy znovu.
      if (receipt.getProcessingStatus() == ReceiptProcessingStatus.PROCESSED) {
        List<ReceiptLine> lines = lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
        matchingService.matchLines(lines, store, owner.getId());
        lineRepository.saveAll(lines);
        lineRepository.flush();
      }
    }
    touchReview(receipt);
    return receipt;
  }

  @Transactional
  public ReceiptLine matchLine(UUID ownerPublicUid, Long lineId, Long productId, boolean newProduct) {
    AppUser owner = requireUser(ownerPublicUid);
    ReceiptLine line = editableLine(lineId, owner);
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    line.setProductId(product.getId());
    line.setMatchSource(newProduct ? ReceiptMatchSource.NEW_PRODUCT : ReceiptMatchSource.USER);
    line.setStatus(ReceiptLineStatus.SUGGESTED);
    line.setSkipReason(null);
    ReceiptLine saved = lineRepository.saveAndFlush(line);
    touchReview(receiptRepository.findById(line.getReceiptId()).orElseThrow());
    return saved;
  }

  @Transactional
  public ReceiptLine ignoreLine(UUID ownerPublicUid, Long lineId) {
    AppUser owner = requireUser(ownerPublicUid);
    ReceiptLine line = editableLine(lineId, owner);
    line.setStatus(ReceiptLineStatus.IGNORED);
    line.setSkipReason(null);
    Receipt receipt = receiptRepository.findById(line.getReceiptId()).orElseThrow();
    ReceiptLine saved = lineRepository.save(line);
    updateReviewStatus(receipt);
    return saved;
  }

  /** Vrátí řádek do výchozího stavu — zruší ruční spárování i ignorování. */
  @Transactional
  public ReceiptLine unmatchLine(UUID ownerPublicUid, Long lineId) {
    AppUser owner = requireUser(ownerPublicUid);
    ReceiptLine line = editableLine(lineId, owner);
    line.setStatus(ReceiptLineStatus.UNMATCHED);
    line.setProductId(null);
    line.setMatchSource(null);
    line.setSkipReason(null);
    Receipt receipt = receiptRepository.findById(line.getReceiptId()).orElseThrow();
    ReceiptLine saved = lineRepository.save(line);
    updateReviewStatus(receipt);
    return saved;
  }

  /**
   * Potvrzení vybraných řádků — každý řádek ve VLASTNÍ transakci. Na rozdíl od
   * {@code submitObservations} (jedna cenovka = jedna dávka) je účtenka desítky položek
   * a kolize jediné z nich (stejné zboží týž den ručně) nesmí shodit ostatní: řádek se
   * přeskočí a vrátí se se {@code skipReason} (docs/rozvoj.md).
   */
  public ConfirmResult confirmLines(UUID ownerPublicUid, Long receiptId, List<Long> lineIds, ObservationSource source) {
    TransactionTemplate readTx = new TransactionTemplate(transactionManager);
    AppUser owner = requireUser(ownerPublicUid);
    Receipt receipt = readTx.execute(status -> ownedReceipt(receiptId, owner));
    if (receipt.getProcessingStatus() != ReceiptProcessingStatus.PROCESSED) {
      throw new ValidationException(ErrorCode.RECEIPT_NOT_PROCESSED);
    }
    if (receipt.getStoreId() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_STORE_REQUIRED);
    }
    if (receipt.getPurchasedAt() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_PURCHASE_TIME_REQUIRED);
    }

    // Všechny řádky se ověří DŘÍV, než se zapíše první cena — neexistující nebo cizí řádek
    // uprostřed by jinak nechal část cen zapsanou a klientovi vrátil jen chybu.
    List<Long> ids = lineIds == null ? List.of() : lineIds;
    Map<Long, ReceiptLine> requested = lineRepository.findAllById(ids).stream()
        .filter(l -> l.getReceiptId().equals(receiptId))
        .collect(Collectors.toMap(ReceiptLine::getId, Function.identity()));
    if (!requested.keySet().containsAll(ids)) {
      throw new NotFoundException(ErrorCode.RECEIPT_LINE_NOT_FOUND);
    }

    TransactionTemplate lineTx = new TransactionTemplate(transactionManager);
    lineTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    int confirmed = 0;
    List<ReceiptLine> skipped = new ArrayList<>();
    for (Long lineId : ids) {
      ReceiptLine line = requested.get(lineId);
      if (line.isConfirmed() || line.getStatus() == ReceiptLineStatus.IGNORED) {
        continue;
      }
      try {
        lineTx.executeWithoutResult(status -> confirmLine(line.getId(), receipt, owner, ownerPublicUid, source));
        confirmed++;
      } catch (AppException e) {
        String reason = DUPLICATE_CODES.contains(e.getCode()) ? SKIP_DUPLICATE : e.getCode().name();
        skipped.add(skip(lineTx, line.getId(), reason, receipt, owner));
      } catch (RuntimeException e) {
        // Neočekávaná chyba jednoho řádku nesmí shodit ostatní ani přeskočit updateReviewStatus.
        log.error("Účtenka {}: řádek {} se nepodařilo potvrdit", receiptId, line.getId(), e);
        skipped.add(skip(lineTx, line.getId(), ErrorCode.RECEIPT_LINE_CONFIRM_FAILED.name(), receipt, owner));
      }
    }
    Receipt result = new TransactionTemplate(transactionManager).execute(status -> {
      Receipt fresh = receiptRepository.findById(receiptId).orElseThrow();
      updateReviewStatus(fresh);
      return fresh;
    });
    log.info("Účtenka {}: potvrzeno {} řádků, přeskočeno {}", receiptId, confirmed, skipped.size());
    return new ConfirmResult(result, confirmed, skipped);
  }

  private ReceiptLine skip(TransactionTemplate lineTx, Long lineId, String reason, Receipt receipt, AppUser owner) {
    return lineTx.execute(status -> {
      ReceiptLine fresh = lineRepository.findById(lineId).orElseThrow();
      fresh.setSkipReason(reason);
      // Duplicita znamená, že tuhle cenu uživatel dnes už zapsal — párování samo je
      // potvrzené, jen druhá cena navíc by porušila denní limit (docs/reputace.md).
      if (SKIP_DUPLICATE.equals(reason) && fresh.getProductId() != null) {
        fresh.setStatus(ReceiptLineStatus.MATCHED);
        learn(fresh, receipt, owner);
      }
      return lineRepository.save(fresh);
    });
  }

  private void confirmLine(Long lineId, Receipt receipt, AppUser owner, UUID ownerPublicUid, ObservationSource source) {
    ReceiptLine line = lineRepository.findById(lineId).orElseThrow();
    if (line.getProductId() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_LINE_NOT_MATCHED);
    }
    Product product = productRepository.findById(line.getProductId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    PriceRows rows = priceRows(line);
    // Druh ceny, který uživatel ten den už zapsal (typicky běžnou cenu ručně), se vynechá —
    // submit by jinak kvůli jedné kolizi shodil celou dávku a cena po slevě by se ztratila.
    Set<String> alreadyToday = Set.copyOf(priceObservationRepository.findPriceKindsBySubmitterOnDay(
        product.getId(), receipt.getStoreId(), owner.getId(), receipt.getPurchasedAt()));
    List<ObservationPriceInput> prices = rows.prices().stream()
        .filter(p -> !alreadyToday.contains(p.priceKind().name()))
        .toList();
    if (prices.isEmpty()) {
      throw new ValidationException(ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY,
          rows.prices().getFirst().priceKind().name());
    }
    List<PriceObservation> saved = priceObservationService.submit(new SubmitObservationsInput(
            product.getId(), receipt.getStoreId(), rows.basis(), receipt.getPurchasedAt(), receipt.getCurrency(),
            null, prices),
        ownerPublicUid, source, EvidenceKind.RECEIPT_OCR, line.getId());
    line.setObservationId(saved.getFirst().getId());
    line.setStatus(ReceiptLineStatus.MATCHED);
    line.setSkipReason(null);
    lineRepository.save(line);
    learn(line, receipt, owner);
  }

  private void learn(ReceiptLine line, Receipt receipt, AppUser owner) {
    Product product = productRepository.findById(line.getProductId()).orElse(null);
    Store store = storeRepository.findById(receipt.getStoreId()).orElse(null);
    if (product != null && store != null) {
      labelService.learnFromLine(product, store, owner.getId(), line.getLabelRaw());
    }
  }

  record PriceRows(QuantityBasis basis, List<ObservationPriceInput> prices) {
  }

  /**
   * Řádek účtenky → ceny k zápisu (docs/rozvoj.md, „Co se nesmí zapomenout"):
   * <ul>
   *   <li>váhové zboží ({@code KG}/{@code L}) se zapisuje jako cena ZA kg/l, nikdy jako
   *       zaplacená částka — ta říká jen, kolik toho člověk koupil;</li>
   *   <li>sleva z vlastního řádku pod položkou dá vedle běžné ceny i cenu po slevě, druh
   *       podle toho, co worker rozpoznal (klubová jen výslovně, jinak akční);</li>
   *   <li>chybí-li jednotková cena, dopočítá se z celkové a množství.</li>
   * </ul>
   */
  static PriceRows priceRows(ReceiptLine line) {
    QuantityBasis basis = switch (line.getQuantityUnit() == null ? QuantityUnit.PCS : line.getQuantityUnit()) {
      case KG -> QuantityBasis.PER_KG;
      case L -> QuantityBasis.PER_L;
      case PCS -> QuantityBasis.PACKAGE;
    };
    BigDecimal quantity = line.getQuantity() != null && line.getQuantity().signum() > 0
        ? line.getQuantity() : BigDecimal.ONE;
    BigDecimal unit = line.getUnitPrice();
    if (unit == null && line.getLineTotal() != null) {
      unit = line.getLineTotal().divide(quantity, 2, RoundingMode.HALF_UP);
    }
    if (unit == null || unit.signum() <= 0) {
      throw new ValidationException(ErrorCode.RECEIPT_LINE_PRICE_MISSING);
    }
    PriceKind guess = line.getPriceKindGuess() == null ? PriceKind.REGULAR : line.getPriceKindGuess();
    List<ObservationPriceInput> prices = new ArrayList<>();
    BigDecimal discount = line.getDiscountAmount();
    if (discount != null && discount.signum() > 0) {
      BigDecimal total = line.getLineTotal() != null ? line.getLineTotal() : unit.multiply(quantity);
      BigDecimal discounted = total.subtract(discount).divide(quantity, 2, RoundingMode.HALF_UP);
      prices.add(price(PriceKind.REGULAR, unit));
      if (discounted.signum() > 0 && discounted.compareTo(unit) < 0) {
        PriceKind kind = guess == PriceKind.CLUB_CARD || guess == PriceKind.CLEARANCE ? guess : PriceKind.PROMO;
        prices.add(price(kind, discounted));
      }
    } else {
      // MULTIBUY z jednoho řádku nerekonstruujeme (chybí počet kusů v akci) — běží jako běžná.
      prices.add(price(guess == PriceKind.MULTIBUY ? PriceKind.REGULAR : guess, unit));
    }
    return new PriceRows(basis, prices);
  }

  private static ObservationPriceInput price(PriceKind kind, BigDecimal amount) {
    return new ObservationPriceInput(kind, amount, null, null, null, null);
  }

  private void touchReview(Receipt receipt) {
    if (receipt.getReviewStatus() == ReceiptReviewStatus.PENDING) {
      receipt.setReviewStatus(ReceiptReviewStatus.IN_PROGRESS);
    }
    receipt.setSeenAt(OffsetDateTime.now());
    receiptRepository.save(receipt);
  }

  /** DONE, když už žádný řádek nečeká na rozhodnutí (spárováno se zápisem, nebo ignorováno). */
  private void updateReviewStatus(Receipt receipt) {
    List<ReceiptLine> lines = lineRepository.findByReceiptIdOrderByLineNoAsc(receipt.getId());
    boolean open = lines.stream().anyMatch(l -> l.getStatus() == ReceiptLineStatus.UNMATCHED
        || l.getStatus() == ReceiptLineStatus.SUGGESTED);
    receipt.setReviewStatus(open ? ReceiptReviewStatus.IN_PROGRESS : ReceiptReviewStatus.DONE);
    receipt.setSeenAt(OffsetDateTime.now());
    receiptRepository.save(receipt);
  }

  private ReceiptLine editableLine(Long lineId, AppUser owner) {
    ReceiptLine line = lineRepository.findById(lineId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_LINE_NOT_FOUND));
    ownedReceipt(line.getReceiptId(), owner);
    if (line.isConfirmed()) {
      // Cena už je zapsaná — přepárování by ji nechalo viset u jiného zboží než řádek.
      throw new ValidationException(ErrorCode.RECEIPT_LINE_ALREADY_CONFIRMED);
    }
    return line;
  }

  private Receipt ownedReceipt(Long receiptId, AppUser owner) {
    return receiptRepository.findByIdAndOwnerUserId(receiptId, owner.getId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
  }

  private AppUser requireUser(UUID publicUid) {
    if (publicUid == null) {
      throw new UnauthorizedException(ErrorCode.RECEIPT_REQUIRES_LOGIN);
    }
    return appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.RECEIPT_REQUIRES_LOGIN));
  }
}
