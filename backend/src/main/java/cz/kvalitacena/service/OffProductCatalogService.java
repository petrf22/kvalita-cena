package cz.kvalitacena.service;

import cz.kvalitacena.controller.CreateProductFromOffInput;
import cz.kvalitacena.controller.UpdateProductInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
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
import java.util.UUID;

/** Atomické založení lokální identity nad OFF snapshotem bez kopírování OFF hodnot do core.*. */
@Service
@RequiredArgsConstructor
public class OffProductCatalogService {

  private final ProductRepository productRepository;
  private final ProductCodeRepository productCodeRepository;
  private final AppUserRepository appUserRepository;
  private final CategoryRepository categoryRepository;
  private final BrandResolutionService brandResolutionService;
  private final CatalogRateLimiter catalogRateLimiter;
  private final OpenFoodFactsService offService;
  private final OffNetContentConverter netContentConverter;
  private final CatalogEditService catalogEditService;
  private final TrustLevelService trustLevelService;
  private final ProductNameWriter productNameWriter;
  private final ProductNameResolver productNameResolver;

  @Transactional
  public Product create(CreateProductFromOffInput input, UUID viewerPublicUid) {
    if (viewerPublicUid == null) throw new UnauthorizedException(ErrorCode.PRODUCT_CREATE_REQUIRES_LOGIN);
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));

    OffLookupResult lookup = offService.lookup(input.code());
    if (lookup.status() == OffLookupStatus.UNAVAILABLE) throw new ValidationException(ErrorCode.OFF_UNAVAILABLE);
    if (lookup.status() != OffLookupStatus.FOUND) throw new ValidationException(ErrorCode.OFF_PRODUCT_NOT_FOUND);
    OffProduct off = lookup.product();
    String gtin = off.getGtin();
    productCodeRepository.findFirstByCodeAndCodeType(gtin, CodeType.GTIN).ifPresent(existing -> {
      throw new DuplicateException(ErrorCode.DUPLICATE_PRODUCT_CODE, existing.getProduct().getId());
    });
    if (!catalogRateLimiter.tryAcquireProductCreation(viewerPublicUid)) throw new TooManyRequestsException();

    // "Má OFF nějaký název?" se ptá napříč VŠEMI jazyky, ne jen v tom klientově — zboží
    // s německým názvem se v české appce založí bez vlastního core.product.name a čeština
    // se doplní až tím, co uživatel do formuláře napíše (updateProduct níž). Kdyby se tady
    // vyžadoval název v jazyce klienta, nešlo by nabízený cizojazyčný název prostě přijmout.
    boolean offHasName = off.getProductName() != null || !off.getNames().isEmpty();
    String fallbackName = offHasName ? null : requiredName(input.name());
    String primaryLang = productNameWriter.primaryLang(input.nameLang(), productNameResolver.requestLanguage());
    Category mappedCategory = off.getMappedCategorySlug() == null ? null
        : categoryRepository.findBySlug(off.getMappedCategorySlug()).orElse(null);
    Category fallbackCategory = mappedCategory == null ? requiredCategory(input.categoryId()) : null;
    OffNetContent offContent = netContentConverter.convert(off);
    UnitBase fallbackUnitBase = offContent == null ? requiredUnitBase(input.unitBase()) : null;
    boolean fallbackVariableWeight = offContent == null && Boolean.TRUE.equals(input.isVariableWeight());
    BigDecimal fallbackBase = offContent == null ? NetContentCalculator.computeNetContentBase(
        fallbackUnitBase, input.netContentValue(), input.netContentUom(), fallbackVariableWeight) : null;

    Product product = Product.builder()
        .name(fallbackName)
        .nameLang(primaryLang)
        .brand(off.getBrandName() == null ? brandResolutionService.resolve(input.brandName()) : null)
        .category(fallbackCategory)
        .unitBase(fallbackUnitBase)
        .netContentValue(offContent == null ? input.netContentValue() : null)
        .netContentUom(offContent == null ? input.netContentUom() : null)
        .netContentBase(fallbackBase)
        .piecesInPack(input.piecesInPack())
        .variableWeight(fallbackVariableWeight)
        .generic(false)
        // Kód sám je dost silná identifikace (ProductCatalogService.create), ale autor od
        // OFF ověřený není — stejný práh důvěry jako u ručně zadaného zboží s kódem
        // (docs/reputace.md, práh T2), jinak by nedůvěryhodný účet OFF cestou obešel DRAFT.
        .status(trustLevelService.isTrusted(user) ? ProductStatus.ACTIVE : ProductStatus.DRAFT)
        .createdByUserId(user.getId())
        .build();
    try {
      product = productRepository.saveAndFlush(product);
      productCodeRepository.saveAndFlush(ProductCode.builder().product(product).code(gtin)
          .codeType(CodeType.GTIN).primary(true).build());
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateException(ErrorCode.DUPLICATE_PRODUCT_CODE, null);
    }

    // updateProduct vrací vždy overlay nad uloženým produktem (nikdy null) — patch, kde se
    // potvrzená hodnota shoduje s OFF/komunitním základem, CatalogEditService sám zahodí.
    UpdateProductInput confirmedValues = new UpdateProductInput(
        input.name(), primaryLang, input.names(), input.brandName(), false, input.categoryId(),
        input.unitBase(), input.netContentValue(), input.netContentUom(), input.piecesInPack(),
        false, input.isVariableWeight());
    return catalogEditService.updateProduct(product.getId(), confirmedValues, viewerPublicUid);
  }

  private String requiredName(String value) {
    if (value == null || value.isBlank()) throw new ValidationException(ErrorCode.PRODUCT_NAME_REQUIRED);
    return value.trim();
  }

  private Category requiredCategory(Long id) {
    if (id == null) throw new ValidationException(ErrorCode.PRODUCT_CATEGORY_REQUIRED);
    return categoryRepository.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));
  }

  private UnitBase requiredUnitBase(UnitBase value) {
    if (value == null) throw new ValidationException(ErrorCode.PRODUCT_UNIT_BASE_REQUIRED);
    return value;
  }
}
