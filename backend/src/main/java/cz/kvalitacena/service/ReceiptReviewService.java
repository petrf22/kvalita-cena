package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.controller.CreateProductFromOffInput;
import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.SubmitObservationsInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.EvidenceKind;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
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
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
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
import java.util.Objects;
import java.util.Optional;
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
  private final ProductCodeRepository productCodeRepository;
  private final AppUserRepository appUserRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final ReceiptMatchingService matchingService;
  private final PriceObservationService priceObservationService;
  private final CurrencyResolver currencyResolver;
  private final ProductStoreLabelService labelService;
  private final OffProductCatalogService offProductCatalogService;
  private final ProductNameResolver productNameResolver;
  private final ProductCatalogService catalogService;
  private final NewProductDefaultsService productDefaultsService;
  private final CategoryRepository categoryRepository;
  private final ReceiptLineChecks lineChecks;
  private final ReceiptProperties receiptProperties;
  private final PlatformTransactionManager transactionManager;

  /** Záchytná kategorie zboží z účtenky, kterému nikdo kategorii nenavrhl (migrace 2026-11-05). */
  static final String UNCATEGORIZED_SLUG = "nezarazeno";

  public record ConfirmResult(Receipt receipt, int confirmed, List<ReceiptLine> skipped) {
  }

  @Transactional
  public Receipt updateHeader(UUID ownerPublicUid, Long receiptId, Long storeId, OffsetDateTime purchasedAt) {
    AppUser owner = requireUser(ownerPublicUid);
    Receipt receipt = ownedReceiptForUpdate(receiptId, owner);
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
      // Součet účtenky se zobrazuje v měně obchodu — stejné, v jaké se pak zapíšou ceny.
      receipt.setCurrency(currencyResolver.forStore(store));
      // Jiný obchod = jiný rozsah označení i vnitroobchodních kódů — strojové návrhy znovu.
      if (receipt.getProcessingStatus() == ReceiptProcessingStatus.PROCESSED) {
        List<ReceiptLine> lines = lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
        matchingService.matchLines(lines, store, owner.getId());
        lineRepository.saveAll(lines);
        lineRepository.flush();
      }
      touchReview(receipt);
      resuggestOpenReceipts(owner, receiptId);
      return receipt;
    }
    touchReview(receipt);
    return receipt;
  }

  /**
   * Obchod potvrzený u jedné účtenky (často právě založený z její hlavičky) může patřit i dalším
   * účtenkám vlastníka, které se rozpoznaly dřív, než obchod existoval — jejich návrh se proto
   * přepočítá. Při změně návrhu se znovu spárují i strojové návrhy řádků, protože naučená
   * označení a vnitroobchodní kódy závisí na obchodě; ruční volby {@code matchLines} nemění.
   */
  private void resuggestOpenReceipts(AppUser owner, Long exceptReceiptId) {
    for (Receipt other : receiptRepository.findOpenWithoutStoreForUpdate(owner.getId(), exceptReceiptId)) {
      Optional<Store> suggested = matchingService.suggestStore(other);
      Long suggestedId = suggested.map(Store::getId).orElse(null);
      if (Objects.equals(suggestedId, other.getSuggestedStoreId())) {
        continue;
      }
      other.setSuggestedStoreId(suggestedId);
      List<ReceiptLine> lines = lineRepository.findByReceiptIdOrderByLineNoAsc(other.getId());
      matchingService.matchLines(lines, suggested.orElse(null), owner.getId());
      lineRepository.saveAll(lines);
      receiptRepository.save(other);
    }
    lineRepository.flush();
  }

  @Transactional
  public ReceiptLine matchLine(UUID ownerPublicUid, Long lineId, Long productId, boolean newProduct) {
    AppUser owner = requireUser(ownerPublicUid);
    ReceiptLine line = editableLine(lineId, owner);
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    line.setProductId(product.getId());
    line.setMatchSource(newProduct ? ReceiptMatchSource.NEW_PRODUCT : ReceiptMatchSource.USER);
    if (Objects.equals(product.getId(), line.getRejectedProductId())) {
      line.setRejectedProductId(null); // ruční volba přebije dřívější odmítnutí
    }
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

  /**
   * Vrátí řádek do výchozího stavu — zruší ruční spárování i ignorování. Zrušené zboží
   * (a u párování podle EAN i samotný EAN) si řádek pamatuje jako odmítnuté: zveřejnění by ho
   * jinak přes naučené označení nebo kód tiše obnovilo, i když klient slíbil samostatné zboží
   * z účtenky ({@link #receiptItemFor}).
   */
  @Transactional
  public ReceiptLine unmatchLine(UUID ownerPublicUid, Long lineId) {
    AppUser owner = requireUser(ownerPublicUid);
    ReceiptLine line = editableLine(lineId, owner);
    if (line.getProductId() != null) {
      line.setRejectedProductId(line.getProductId());
    }
    if (line.getMatchSource() == ReceiptMatchSource.CODE || line.getMatchSource() == ReceiptMatchSource.OFF) {
      line.setCodeRejected(true);
    }
    line.setStatus(ReceiptLineStatus.UNMATCHED);
    line.setProductId(null);
    line.setMatchSource(null);
    line.setSkipReason(null);
    Receipt receipt = receiptRepository.findById(line.getReceiptId()).orElseThrow();
    ReceiptLine saved = lineRepository.save(line);
    updateReviewStatus(receipt);
    return saved;
  }

  public ConfirmResult confirmLines(UUID ownerPublicUid, Long receiptId, List<Long> lineIds, ObservationSource source) {
    return confirmLines(ownerPublicUid, receiptId, lineIds, source, false);
  }

  /**
   * Potvrzení vybraných řádků — každý řádek ve VLASTNÍ transakci. Na rozdíl od
   * {@code submitObservations} (jedna cenovka = jedna dávka) je účtenka desítky položek
   * a kolize jediné z nich (stejné zboží týž den ručně) nesmí shodit ostatní: řádek se
   * přeskočí a vrátí se se {@code skipReason} (docs/rozvoj.md).
   *
   * <p>S {@code publishUnmatched} se zveřejní i řádek bez zboží: cena se zapíše na „zboží
   * z účtenky" ({@link #receiptItemFor}). Bez něj se nespárovaný řádek přeskočí jako dřív
   * ({@code RECEIPT_LINE_NOT_MATCHED}) — starší klienti posílají jen spárované řádky.
   */
  public ConfirmResult confirmLines(UUID ownerPublicUid, Long receiptId, List<Long> lineIds, ObservationSource source,
      boolean publishUnmatched) {
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
        lineTx.executeWithoutResult(status ->
            confirmLine(line.getId(), receipt, owner, ownerPublicUid, source, publishUnmatched));
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
      Receipt fresh = receiptRepository.findForUpdateById(receiptId).orElseThrow();
      if (publishUnmatched) {
        ignoreNonProducts(receiptId);
      }
      updateReviewStatus(fresh);
      return fresh;
    });
    log.info("Účtenka {}: potvrzeno {} řádků, přeskočeno {}", receiptId, confirmed, skipped.size());
    return new ConfirmResult(result, confirmed, skipped);
  }

  /**
   * Záloha a vratný obal se nezveřejní nikdy ({@link ReceiptLineChecks#isNonProduct}) — klient
   * je ani nenabízí. Po „Zveřejnit vybrané" se proto nespárované nezboží označí jako ignorované,
   * jinak by účtenka kvůli záloze na lahev zůstala navždy rozpracovaná. Vrátit jde jako každé
   * ignorování ({@link #unmatchLine}).
   */
  private void ignoreNonProducts(Long receiptId) {
    List<ReceiptLine> nonProducts = lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId).stream()
        .filter(l -> l.getStatus() == ReceiptLineStatus.UNMATCHED && lineChecks.isNonProduct(l))
        .toList();
    nonProducts.forEach(l -> {
      l.setStatus(ReceiptLineStatus.IGNORED);
      l.setSkipReason(null);
    });
    lineRepository.saveAll(nonProducts);
  }

  private ReceiptLine skip(TransactionTemplate lineTx, Long lineId, String reason, Receipt receipt, AppUser owner) {
    return lineTx.execute(status -> {
      ReceiptLine fresh = lineRepository.findById(lineId).orElseThrow();
      fresh.setSkipReason(reason);
      // Duplicita znamená, že tuhle cenu uživatel dnes už zapsal — párování samo je
      // potvrzené, jen druhá cena navíc by porušila denní limit (docs/reputace.md).
      // OFF návrh zboží nemá a doplněné productId z transakce řádku zmizelo s jejím rollbackem.
      // Duplicitní cenu jde zapsat jen k zboží, které už existuje — dohledá se podle EAN.
      if (SKIP_DUPLICATE.equals(reason) && fresh.getProductId() == null
          && fresh.getMatchSource() == ReceiptMatchSource.OFF) {
        existingProductForCode(fresh).ifPresent(fresh::setProductId);
      }
      if (SKIP_DUPLICATE.equals(reason) && fresh.getProductId() != null) {
        fresh.setStatus(ReceiptLineStatus.MATCHED);
        learn(fresh, receipt, owner);
      }
      return lineRepository.save(fresh);
    });
  }

  private void confirmLine(Long lineId, Receipt receipt, AppUser owner, UUID ownerPublicUid, ObservationSource source,
      boolean publishUnmatched) {
    ReceiptLine line = lineRepository.findById(lineId).orElseThrow();
    // Cena se ověří dřív, než vznikne zboží z účtenky — řádek bez ceny by ho jinak založil
    // a hned zahodil rollbackem.
    PriceRows rows = priceRows(line);
    if (line.getProductId() == null && line.getMatchSource() == ReceiptMatchSource.OFF) {
      line.setProductId(productFromOff(line, ownerPublicUid));
    }
    if (line.getProductId() == null && publishUnmatched) {
      receiptItemFor(line, receipt, owner);
    }
    if (line.getProductId() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_LINE_NOT_MATCHED);
    }
    Product product = productRepository.findById(line.getProductId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
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
    // Měna null = měna provozovny (CurrencyResolver.forStore). receipt.currency NE: měnu
    // z tisku worker nečte, profil ji jen předpokládá (CZK), a jako input.currency by prošla
    // whitelistem a přebila zemi obchodu — 1,29 € ze slovenské účtenky by se zapsalo jako Kč.
    List<PriceObservation> saved = priceObservationService.submit(new SubmitObservationsInput(
            product.getId(), receipt.getStoreId(), rows.basis(), receipt.getPurchasedAt(), null,
            null, prices),
        ownerPublicUid, source, EvidenceKind.RECEIPT_OCR, line.getId());
    line.setObservationId(saved.getFirst().getId());
    line.setStatus(ReceiptLineStatus.MATCHED);
    line.setSkipReason(null);
    lineRepository.save(line);
    learn(line, receipt, owner);
  }

  /**
   * Zboží pro řádek zveřejněný bez spárování (docs/datovy-model.md, „Účtenky"). Nejdřív
   * naučené označení — mohlo vzniknout až po zpracování účtenky, třeba z jiné účtenky téhož
   * řetězce —, pak zboží s EAN řádku (mezitím ho mohl založit někdo jiný), jinak „zboží
   * z účtenky" ({@link ProductCatalogService#receiptItem}): s platným EAN globální zboží s tím
   * kódem, bez něj existující druhová položka se stejným názvem v rozsahu prodejce, nebo nová
   * {@code DRAFT} s názvem, kategorií a gramáží z předvyplnění formuláře
   * ({@link NewProductDefaultsService#forLine}). Platný EAN = GTIN s kontrolní číslicí
   * ({@link GtinNormalization#isGlobalGtin}); vnitřní kód obchodu zůstane jen na řádku.
   * Bez navržené kategorie dostane záchytnou „Nezařazeno". Záloha a vratný obal zboží nejsou
   * a nezveřejňují se.
   */
  private void receiptItemFor(ReceiptLine line, Receipt receipt, AppUser owner) {
    if (lineChecks.isNonProduct(line)) {
      throw new ValidationException(ErrorCode.RECEIPT_LINE_NOT_PRODUCT);
    }
    Store store = storeRepository.findById(receipt.getStoreId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
    Long rejected = line.getRejectedProductId();
    Optional<Long> labelled = matchingService.byLabel(line, store, owner.getId())
        .filter(id -> !id.equals(rejected));
    if (labelled.isPresent()) {
      line.setProductId(labelled.get());
      line.setMatchSource(ReceiptMatchSource.LABEL);
      return;
    }
    String gtin14 = !line.isCodeRejected() && GtinNormalization.isGlobalGtin(line.getInternalCode())
        ? GtinNormalization.toGtin14(line.getInternalCode().trim()) : null;
    if (gtin14 != null) {
      Optional<Long> coded = existingProductForCode(line);
      if (coded.isPresent() && !coded.get().equals(rejected)) {
        line.setProductId(coded.get());
        line.setMatchSource(ReceiptMatchSource.CODE);
        return;
      }
      if (coded.isPresent()) {
        gtin14 = null; // kód patří odmítnutému zboží — zbude bezkódové zboží z účtenky
      }
    }
    NewProductDefaultsService.Defaults defaults = productDefaultsService.forLine(line, store.getId(), owner.getId());
    Category category = defaults.category() != null ? defaults.category()
        : categoryRepository.findBySlug(UNCATEGORIZED_SLUG)
            .orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));
    NetContentUom uom = defaults.isVariableWeight()
        ? (line.getQuantityUnit() == QuantityUnit.L ? NetContentUom.L : NetContentUom.KG)
        : defaults.netContentUom();
    ProductCatalogService.ReceiptItem item = catalogService.receiptItem(defaults.name(), gtin14, category,
        defaults.netContentValue(), uom, defaults.isVariableWeight(), store, owner,
        receiptProperties.getMaxOpenItems());
    if (item.product().getId().equals(rejected)) {
      // Stejnojmenné zboží z účtenky v rozsahu je právě to odmítnuté — druhé se stejným názvem
      // vzniknout nemůže (uq_product_generic_name_scope) a zapsat cenu k odmítnutému nesmíme.
      throw new ValidationException(ErrorCode.RECEIPT_LINE_MATCH_REJECTED);
    }
    if (item.created()) {
      log.info("Účtenka {}: řádek {} založil zboží z účtenky {}", receipt.getId(), line.getId(), item.product().getId());
    }
    line.setProductId(item.product().getId());
    line.setMatchSource(ReceiptMatchSource.RECEIPT_ITEM);
  }

  /**
   * Zboží pro OFF návrh (ReceiptMatchingService.offCandidate) — založené až teď, na pokyn
   * vlastníka účtenky, přes {@link OffProductCatalogService#create}: v {@code core.product}
   * zůstanou OFF hodnoty {@code NULL} (ODbL, docs/datovy-model.md), posílá se jen kód.
   *
   * <p>Název z řádku (čitelný název, je to vlastní text) se pošle jen tehdy, když OFF název
   * v jazyce appky nemá — stejné pravidlo jako formulář: cizojazyčný název z OFF se nikdy
   * nevydává za český, a poslat název, který OFF v jazyce appky má, by z něj udělalo osobní
   * patch. Zboží, které mezitím někdo s tímhle EAN založil, se použije místo nového — kontrola
   * předem, protože výjimka z transakční metody by označila celou transakci řádku k rollbacku.
   */
  private Long productFromOff(ReceiptLine line, UUID ownerPublicUid) {
    Optional<Long> existing = existingProductForCode(line);
    if (existing.isPresent()) {
      return existing.get();
    }
    OffProduct off = matchingService.offCandidate(line)
        .orElseThrow(() -> new ValidationException(ErrorCode.OFF_PRODUCT_NOT_FOUND));
    String lang = productNameResolver.requestLanguage();
    boolean offHasAnyName = off.getProductName() != null || !off.getNames().isEmpty();
    String name = null;
    if (!off.getNames().containsKey(lang)) {
      name = line.getLabelDisplay() != null ? line.getLabelDisplay() : offHasAnyName ? null : line.getLabelRaw();
    }
    Product product = offProductCatalogService.create(new CreateProductFromOffInput(
        line.getInternalCode().trim(), name, lang, List.of(), null, null, null, null, null, null, null), ownerPublicUid);
    log.info("Účtenka: řádek {} založil zboží {} z Open Food Facts", line.getId(), product.getId());
    return product.getId();
  }

  private Optional<Long> existingProductForCode(ReceiptLine line) {
    String gtin14 = GtinNormalization.toGtin14(line.getInternalCode().trim());
    return productCodeRepository.findFirstByCodeAndCodeType(gtin14, CodeType.GTIN)
        .map(code -> code.getProduct().getId());
  }

  private void learn(ReceiptLine line, Receipt receipt, AppUser owner) {
    Product product = productRepository.findById(line.getProductId()).orElse(null);
    Store store = storeRepository.findById(receipt.getStoreId()).orElse(null);
    if (product != null && store != null) {
      labelService.learnFromLine(product, store, owner.getId(), line.getLabelRaw());
    }
    if (product != null) {
      learnCode(line, product);
    }
  }

  /**
   * EAN z potvrzeného řádku ke zboží, které EAN zatím nemá. Zapisuje se globálně, ne do
   * osobního patche: kód, který zboží nemá vůbec, nic nepřepisuje. Je to zaplnění díry
   * jako doplnění chybějícího jazyka názvu (docs/datovy-model.md, „Uživatelská vrstva nad
   * globálními daty"). Zboží, které už nějaký EAN má, se nemění. Omylem spárovaný řádek by
   * jinak přilepil cizí kód ke zboží pro všechny, a víc EAN u jednoho zboží (jiné balení,
   * nový obal) je spíš věc moderace.
   * Bezkódová položka ({@code generic}, třeba rohlík) EAN záměrně nemá a nedostane ho.
   */
  private void learnCode(ReceiptLine line, Product product) {
    String code = line.getInternalCode();
    if (!GtinNormalization.isGlobalGtin(code) || product.isGeneric()
        || (product.getStatus() != ProductStatus.ACTIVE && product.getStatus() != ProductStatus.DRAFT)) {
      return;
    }
    String gtin14 = GtinNormalization.toGtin14(code.trim());
    if (productCodeRepository.insertGtinIfProductHasNone(product.getId(), gtin14) == 1) {
      log.info("Účtenka: zboží {} dostalo EAN {} z řádku {}", product.getId(), gtin14, line.getId());
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
   *   <li>chybí-li jednotková cena, dopočítá se z celkové a množství — u váhového zboží jen
   *       se známým množstvím, jinak by se zaplacená částka zapsala jako cena za kg/l.</li>
   * </ul>
   */
  static PriceRows priceRows(ReceiptLine line) {
    QuantityBasis basis = switch (line.getQuantityUnit() == null ? QuantityUnit.PCS : line.getQuantityUnit()) {
      case KG -> QuantityBasis.PER_KG;
      case L -> QuantityBasis.PER_L;
      case PCS -> QuantityBasis.PACKAGE;
    };
    boolean knownQuantity = line.getQuantity() != null && line.getQuantity().signum() > 0;
    // Bez množství jde cenu za jednotku odvodit z celkové částky jen u kusů (1 ks = balení);
    // u kg/l by zaplacených 20,50 Kč bylo „20,50 Kč/kg", ať zboží stojí kolik chce.
    boolean perUnitDerivable = knownQuantity || basis == QuantityBasis.PACKAGE;
    BigDecimal quantity = knownQuantity ? line.getQuantity() : BigDecimal.ONE;
    BigDecimal unit = line.getUnitPrice();
    if (unit == null && line.getLineTotal() != null && perUnitDerivable) {
      unit = line.getLineTotal().divide(quantity, 2, RoundingMode.HALF_UP);
    }
    if (unit == null || unit.signum() <= 0) {
      throw new ValidationException(ErrorCode.RECEIPT_LINE_PRICE_MISSING);
    }
    PriceKind guess = line.getPriceKindGuess() == null ? PriceKind.REGULAR : line.getPriceKindGuess();
    List<ObservationPriceInput> prices = new ArrayList<>();
    BigDecimal discount = line.getDiscountAmount();
    if (discount != null && discount.signum() > 0 && !perUnitDerivable) {
      // Sleva váhového zboží bez množství — cenu po slevě za kg/l spočítat nejde, zapíše se
      // jen běžná cena z tištěné jednotkové ceny.
      prices.add(price(PriceKind.REGULAR, unit));
    } else if (discount != null && discount.signum() > 0) {
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
    receipt.setReviewStatus(reviewStatusOf(lineRepository.findByReceiptIdOrderByLineNoAsc(receipt.getId())));
    receipt.setSeenAt(OffsetDateTime.now());
    receiptRepository.save(receipt);
  }

  /** Stav revize rozpracované účtenky podle řádků — sdílí ho i nové rozpoznání (ReceiptWorkerService). */
  static ReceiptReviewStatus reviewStatusOf(List<ReceiptLine> lines) {
    boolean open = lines.stream().anyMatch(l -> l.getStatus() == ReceiptLineStatus.UNMATCHED
        || l.getStatus() == ReceiptLineStatus.SUGGESTED);
    return open ? ReceiptReviewStatus.IN_PROGRESS : ReceiptReviewStatus.DONE;
  }

  private ReceiptLine editableLine(Long lineId, AppUser owner) {
    ReceiptLine line = lineRepository.findById(lineId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_LINE_NOT_FOUND));
    // Se zámkem: úprava řádku pak zapisuje stav revize účtenky (touchReview/updateReviewStatus).
    ownedReceiptForUpdate(line.getReceiptId(), owner);
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

  /**
   * Účtenka vlastníka se zámkem — úprava se řadí za souběžný zápis workeru (nové rozpoznání),
   * ať jeden nepřepíše druhému hlavičku (ReceiptRepository.findForUpdateById).
   */
  private Receipt ownedReceiptForUpdate(Long receiptId, AppUser owner) {
    return receiptRepository.findForUpdateByIdAndOwnerUserId(receiptId, owner.getId())
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
