package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.CreateProductInput;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductCreatedVia;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Založení zboží — s naskenovaným EANem i bez něj. Bezkódové zboží ("pečivo za 45 Kč" z
 * účtenky, "brambory" z podnikové prodejny bez EANu) vzniká jako druhová položka
 * (Product.isGeneric) se statusem DRAFT, dokud ji nepotvrdí víc přispěvatelů — viz
 * docs/reputace.md, "Zboží bez čárového kódu". Confidence agregátu pro takovou položku
 * zastropovává {@link PriceAggregationService}, ne váha záznamu (ta by v buňce
 * (produkt, obchod) byla vůči váženému mediánu i Kishovu n_eff beze změny, protože
 * multiplikuje všechny záznamy stejně).
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogService {

  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final BrandResolutionService brandResolutionService;
  private final CategoryRepository categoryRepository;
  private final ProductCodeRepository productCodeRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final AppUserRepository appUserRepository;
  private final CatalogProperties catalogProperties;
  private final CatalogRateLimiter catalogRateLimiter;
  private final DuplicateLookupService duplicateLookupService;
  private final TrustLevelService trustLevelService;
  private final ProductScopeService productScopeService;
  private final ProductNameWriter productNameWriter;
  private final ProductNameResolver productNameResolver;

  @Transactional
  public Product create(CreateProductInput input, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.PRODUCT_CREATE_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));

    if (input.name() == null || input.name().isBlank()) {
      throw new ValidationException(ErrorCode.PRODUCT_NAME_REQUIRED);
    }
    if (input.categoryId() == null) {
      throw new ValidationException(ErrorCode.PRODUCT_CATEGORY_REQUIRED);
    }
    if (input.unitBase() == null) {
      throw new ValidationException(ErrorCode.PRODUCT_UNIT_BASE_REQUIRED);
    }
    Category category = categoryRepository.findById(input.categoryId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));

    String code = blankToNull(input.code());
    String gtin14 = code == null ? null : GtinNormalization.toGtin14(code);
    if (gtin14 != null) {
      productCodeRepository.findFirstByCodeAndCodeType(gtin14, CodeType.GTIN).ifPresent(existing -> {
        throw new DuplicateException(ErrorCode.DUPLICATE_PRODUCT_CODE, existing.getProduct().getId());
      });
    }
    if (!catalogRateLimiter.tryAcquireProductCreation(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    boolean variableWeight = input.isVariableWeight() != null && input.isVariableWeight();
    BigDecimal netContentBase = NetContentCalculator.computeNetContentBase(input.unitBase(),
        input.netContentValue(), input.netContentUom(), variableWeight);
    boolean generic = gtin14 == null;
    Store scopeStore = null;
    if (generic) {
      if (input.storeId() == null) {
        throw new ValidationException(ErrorCode.PRODUCT_STORE_REQUIRED);
      }
      scopeStore = storeRepository.findById(input.storeId())
          .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
    }
    if (scopeStore != null) {
      // Dva stropy nad rámec denního počtu položek, oba mířené na bezkódové zboží. Rozsah
      // lokální položky je jeden obchod, takže strop na obchod drží případnou škodu tam, kde
      // vznikla; strop na otevřené nepotvrzené položky pak zavírá škálování časem, na které
      // denní limit nestačí (docs/reputace.md, "Zboží bez čárového kódu").
      // Zboží z účtenek má vlastní strop (createReceiptItem) — jedna velká účtenka nesmí
      // zablokovat ruční zakládání.
      long openDrafts = productRepository.countByCreatedByUserIdAndGenericAndStatusAndCreatedVia(
          user.getId(), true, ProductStatus.DRAFT, ProductCreatedVia.USER);
      if (openDrafts >= catalogProperties.getMaxUnconfirmedDrafts()) {
        throw new TooManyRequestsException(ErrorCode.PRODUCT_TOO_MANY_UNCONFIRMED);
      }
      if (!catalogRateLimiter.tryAcquireProductCreationInStore(viewerPublicUid, scopeStore.getId())) {
        throw new TooManyRequestsException();
      }
    }

    boolean trusted = trustLevelService.isTrusted(user);
    String primaryLang = productNameWriter.primaryLang(input.nameLang(), productNameResolver.requestLanguage());

    Product product = Product.builder()
        .name(input.name().trim())
        .nameLang(primaryLang)
        .brand(brandResolutionService.resolve(input.brandName()))
        .category(category)
        .unitBase(input.unitBase())
        .netContentValue(input.netContentValue())
        .netContentUom(input.netContentUom())
        .netContentBase(netContentBase)
        .piecesInPack(input.piecesInPack())
        .variableWeight(variableWeight)
        .generic(generic)
        // DRAFT ze dvou nezávislých důvodů, oba se řeší stejnou promocí (promoteIfConfirmed):
        // bezkódová položka VŽDY (kód sám je dost silná identifikace, aby duplicitu nešlo
        // snadno založit znovu), zboží od nedůvěryhodného autora taky (docs/reputace.md,
        // práh důvěry jako etapa-1 aproximace T2) — dokud ho nepotvrdí víc přispěvatelů.
        .status(generic || !trusted ? ProductStatus.DRAFT : ProductStatus.ACTIVE)
        .createdByUserId(user.getId())
        .build();

    if (scopeStore != null) {
      productScopeService.assignLocalScope(product, scopeStore);
    }

    try {
      product = productRepository.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      throw duplicateGenericOf(input.name().trim(), scopeStore == null ? null : scopeStore.getId());
    }

    if (gtin14 != null) {
      productCodeRepository.save(ProductCode.builder()
          .product(product)
          .code(gtin14)
          .codeType(CodeType.GTIN)
          .primary(true)
          .build());
    }

    // Další jazyky jdou do core.product_name — u právě zakládaného zboží není s čím
    // nesouhlasit, takže globálně (docs/lokalizace.md).
    productNameWriter.applyOnCreate(product, input.name(), primaryLang, input.names(), user);

    return product;
  }

  /** Zboží z účtenky k řádku: existující položka v rozsahu prodejce, nebo nově založená. */
  public record ReceiptItem(Product product, boolean created) {
  }

  /**
   * „Zboží z účtenky" (docs/reputace.md, „Zboží z účtenky") — položka pro řádek účtenky, který
   * vlastník zveřejnil bez spárování. Bez EAN je to druhová položka {@code DRAFT} v rozsahu
   * řetězce/provozovny jako ruční bezkódové zboží; s platným EAN z řádku ({@code gtin14}, který
   * nezná katalog ani Open Food Facts) globální zboží s tím kódem jako po skenu čárového kódu —
   * jinak by ho pozdější sken ani účtenka jiného řetězce nenašly. Obojí se značkou
   * {@code created_via = RECEIPT}, vždy {@code DRAFT} (název je přepis z tisku pokladny) a s vlastním
   * stropem {@code app.receipt.max-open-items} místo limitů ručního zakládání ({@code
   * app.catalog.*}): účtenka se 40 nespárovanými řádky by je vyčerpala celé a autor by pak
   * nemohl založit nic ani ručně. Spam brzdí denní limit účtenek a to, že každá prošla workerem.
   *
   * <p>Bezkódová položka se stejným normalizovaným názvem v rozsahu se použije znovu (i ruční),
   * ať se ceny různých lidí sbíhají na jedno zboží; zboží s EAN se pozná podle kódu
   * (ReceiptReviewService.receiptItemFor ho hledá dřív, než sem dojde). Volá se uvnitř transakce řádku
   * (ReceiptReviewService.confirmLines), proto se kolize unikátního indexu neřeší výjimkou —
   * ta by transakci řádku označila k rollbacku —, ale dotazem předem.
   */
  @Transactional
  public ReceiptItem receiptItem(String name, String gtin14, Category category, BigDecimal netContentValue,
      NetContentUom netContentUom, boolean variableWeight, Store store, AppUser user, int maxOpenItems) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      throw new ValidationException(ErrorCode.PRODUCT_NAME_REQUIRED);
    }
    if (trimmed.length() > 200) {
      trimmed = trimmed.substring(0, 200).trim();
    }
    boolean generic = gtin14 == null;
    if (generic) {
      Long chainId = store.getChain() != null ? store.getChain().getId() : null;
      Optional<Product> existing = productRepository.findGenericInScopeByName(trimmed, chainId, store.getId());
      if (existing.isPresent()) {
        return new ReceiptItem(existing.get(), false);
      }
    }
    long open = productRepository.countByCreatedByUserIdAndStatusAndCreatedVia(
        user.getId(), ProductStatus.DRAFT, ProductCreatedVia.RECEIPT);
    if (open >= maxOpenItems) {
      throw new TooManyRequestsException(ErrorCode.RECEIPT_ITEM_LIMIT, maxOpenItems);
    }
    // Gramáž jen jako úplná dvojice (CLAUDE.md, „Gramáž/objem VŽDY jako dvojice"); jednotka
    // určí unitBase stejně jako formulář (unitBaseForUom), bez gramáže je cena za balení.
    boolean pair = !variableWeight && netContentValue != null && netContentValue.signum() > 0 && netContentUom != null;
    BigDecimal value = pair ? netContentValue : null;
    NetContentUom uom = pair ? netContentUom : null;
    // Váhové zboží gramáž ignoruje, jednotka (KG/L z řádku) rozhodne jen MASS/VOLUME.
    boolean volume = netContentUom == NetContentUom.ML || netContentUom == NetContentUom.L;
    UnitBase unitBase = variableWeight ? (volume ? UnitBase.VOLUME : UnitBase.MASS)
        : uom == null ? UnitBase.COUNT
        : volume ? UnitBase.VOLUME : UnitBase.MASS;
    String lang = productNameResolver.requestLanguage();
    Product product = Product.builder()
        .name(trimmed)
        .nameLang(productNameWriter.primaryLang(null, lang))
        .category(category)
        .unitBase(unitBase)
        .netContentValue(value)
        .netContentUom(uom)
        .netContentBase(NetContentCalculator.computeNetContentBase(unitBase, value, uom, variableWeight))
        .variableWeight(variableWeight)
        .generic(generic)
        .status(ProductStatus.DRAFT)
        .createdVia(ProductCreatedVia.RECEIPT)
        .createdByUserId(user.getId())
        .build();
    if (generic) {
      productScopeService.assignLocalScope(product, store);
    }
    product = productRepository.saveAndFlush(product);
    if (!generic) {
      productCodeRepository.save(ProductCode.builder()
          .product(product)
          .code(gtin14)
          .codeType(CodeType.GTIN)
          .primary(true)
          .build());
    }
    return new ReceiptItem(product, true);
  }

  /**
   * Volá {@link PriceObservationService#submit} po každém zápisu ceny k DRAFT produktu —
   * jakmile ho potvrdí dost RŮZNÝCH přispěvatelů (app.catalog.draft-confirmations), překlopí
   * se na ACTIVE. Žádný plánovač navíc, kontrola je jen dotaz + případný jeden UPDATE.
   * Leave-one-out (docs/reputace.md) — počítají se jen potvrzení od JINÝCH uživatelů než
   * autora, stejně jako u PENDING obchodu (StoreService.promoteIfConfirmed), jinak by si
   * zakladatel odemkl vlastní DRAFT sám vlastními zápisy cen.
   */
  @Transactional
  public void promoteIfConfirmed(Long productId) {
    Product product = productRepository.findById(productId).orElse(null);
    if (product == null || product.getStatus() != ProductStatus.DRAFT) return;

    long contributors = priceObservationRepository.countDistinctProductContributorsExcluding(
        productId, product.getCreatedByUserId());
    if (contributors >= catalogProperties.getDraftConfirmations()) {
      product.setStatus(ProductStatus.ACTIVE);
      productRepository.save(product);
    }
  }

  /**
   * Stav zveřejnění produktu (docs/reputace.md, "Práh důvěry pro zveřejnění nového záznamu") —
   * stejná logika jako {@code MyContributionsService.productStatus} (výpis "Moje příspěvky"),
   * tady navíc pro moderátorský výpis cen ({@link ModerationService#moderationObservations}),
   * který produkt vidí i mimo kontext "moje", takže autor ≠ viewer. Duplicita mezi oběma
   * službami je vědomá — spojit by šlo jen zavlečením store-specifické logiky
   * {@code MyContributionsService} sem, kam nepatří.
   */
  public PublicationStatus productStatus(Product product, long confirmationsReceived) {
    if (product.getStatus() == ProductStatus.MERGED) return PublicationStatus.merged();
    if (product.getHiddenAt() != null) return PublicationStatus.hiddenAfterFlags();
    if (product.getStatus() == ProductStatus.DRAFT) {
      return PublicationStatus.awaitingConfirmations((int) confirmationsReceived, catalogProperties.getDraftConfirmations());
    }
    return PublicationStatus.publicState(product.isVerified());
  }

  /** Dávkové leave-one-out potvrzení pro víc DRAFT produktů najednou, viz {@link #promoteIfConfirmed}. */
  public Map<Long, Long> confirmationsForProducts(List<Product> products) {
    List<Long> draftIds = products.stream()
        .filter(p -> p.getStatus() == ProductStatus.DRAFT)
        .map(Product::getId)
        .toList();
    if (draftIds.isEmpty()) return Map.of();
    return priceObservationRepository.countDistinctProductContributorsExcludingBatch(draftIds).stream()
        .collect(Collectors.toMap(PriceObservationRepository.ContributorCount::getId,
            PriceObservationRepository.ContributorCount::getCnt));
  }

  private DuplicateException duplicateGenericOf(String name, Long storeId) {
    // Vlastní transakce (DuplicateLookupService) — viz StoreService.duplicateOf, stejný důvod.
    List<Product> similar = duplicateLookupService.findSimilarProducts(name, storeId);
    Long existingId = similar.isEmpty() ? null : similar.get(0).getId();
    return new DuplicateException(ErrorCode.DUPLICATE_GENERIC_PRODUCT, existingId);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
