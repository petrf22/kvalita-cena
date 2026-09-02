package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.CreateProductInput;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
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
  private final BrandResolutionService brandResolutionService;
  private final CategoryRepository categoryRepository;
  private final ProductCodeRepository productCodeRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final AppUserRepository appUserRepository;
  private final CatalogProperties catalogProperties;
  private final CatalogRateLimiter catalogRateLimiter;
  private final DuplicateLookupService duplicateLookupService;
  private final TrustLevelService trustLevelService;

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
    boolean trusted = trustLevelService.isTrusted(user);

    Product product = Product.builder()
        .name(input.name().trim())
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

    try {
      product = productRepository.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      throw duplicateGenericOf(input.name().trim(), category.getId());
    }

    if (gtin14 != null) {
      productCodeRepository.save(ProductCode.builder()
          .product(product)
          .code(gtin14)
          .codeType(CodeType.GTIN)
          .primary(true)
          .build());
    }

    return product;
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

  private DuplicateException duplicateGenericOf(String name, Long categoryId) {
    // Vlastní transakce (DuplicateLookupService) — viz StoreService.duplicateOf, stejný důvod.
    List<Product> similar = duplicateLookupService.findSimilarProducts(name);
    Long existingId = similar.isEmpty() ? null : similar.get(0).getId();
    return new DuplicateException(ErrorCode.DUPLICATE_GENERIC_PRODUCT, existingId);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
