package cz.kvalitacena.service;

import cz.kvalitacena.controller.UpdateProductInput;
import cz.kvalitacena.controller.UpdateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Brand;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CodeType;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCode;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.OffProductRepository;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Úprava existujícího zboží/obchodu jako patch nad core.product_user_edit /
 * core.store_user_edit — globální řádek se nikdy nesahá (docs/datovy-model.md, "Uživatelská
 * vrstva nad globálními daty"). Ukládá se jen pole ODLIŠNÉ od aktuální globální hodnoty; shodná
 * hodnota patch pole naopak VYNULUJE (uživatel se tím vrátí k výchozí globální hodnotě, patch
 * na ni už nemusí ukazovat sám na sebe). Prázdný patch (žádné pole, žádné cleared_fields) se
 * vůbec neukládá / smaže se, pokud existoval — uživatel se tak může "vrátit" na globální stav.
 */
@Service
@RequiredArgsConstructor
public class CatalogEditService {

  private final ProductRepository productRepository;
  private final ProductUserEditRepository productUserEditRepository;
  private final CategoryRepository categoryRepository;
  private final BrandResolutionService brandResolutionService;
  private final ProductOverlayService productOverlayService;
  private final ProductNameWriter productNameWriter;
  private final ProductNameResolver productNameResolver;
  private final OffProductRepository offProductRepository;
  private final ProductCodeRepository productCodeRepository;

  private final StoreRepository storeRepository;
  private final StoreUserEditRepository storeUserEditRepository;
  private final RetailChainRepository retailChainRepository;
  private final CompanyIdValidators companyIdValidators;
  private final StoreOverlayService storeOverlayService;
  private final CountryResolver countryResolver;
  private final TrustLevelService trustLevelService;

  private final AppUserRepository appUserRepository;

  @Transactional
  public Product updateProduct(Long productId, UpdateProductInput input, UUID viewerPublicUid) {
    AppUser user = requireUser(viewerPublicUid, ErrorCode.PRODUCT_EDIT_REQUIRES_LOGIN);
    Product storedProduct = productRepository.findById(productId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    Product overlaidProduct = productOverlayService.applyOverlay(storedProduct, null);
    Product product = overlaidProduct == null ? storedProduct : overlaidProduct;

    Optional<ProductUserEdit> existing = productUserEditRepository.findByProductIdAndUserId(productId, user.getId());
    ProductUserEdit edit = existing.orElseGet(() ->
        ProductUserEdit.builder().productId(productId).userId(user.getId()).build());
    List<String> cleared = new ArrayList<>(edit.getClearedFields());

    // Název je jazyková věc, ne jednohodnotová: doplnění chybějícího jazyka jde globálně,
    // změna existujícího do patche (docs/lokalizace.md). Rozhoduje o tom ProductNameWriter,
    // který k tomu potřebuje ULOŽENOU entitu a OFF snapshot, ne překrytou kopii.
    if (input.name() != null || (input.names() != null && !input.names().isEmpty())) {
      productNameWriter.apply(storedProduct, offSnapshotFor(productId), edit, input.name(),
          productNameWriter.primaryLang(input.nameLang(), productNameResolver.requestLanguage()),
          input.names(), user);
      productRepository.save(storedProduct);
    }

    if (input.brandName() != null) {
      String brandName = input.brandName().trim();
      String effectiveBrandName = product.getExternalBrandName() != null
          ? product.getExternalBrandName() : product.getBrand() == null ? null : product.getBrand().getName();
      if (effectiveBrandName != null && effectiveBrandName.equalsIgnoreCase(brandName)) {
        edit.setBrandId(null);
      } else {
        Brand brand = brandResolutionService.resolve(brandName);
        Long globalBrandId = product.getBrand() == null ? null : product.getBrand().getId();
        Long newBrandId = brand == null ? null : brand.getId();
        edit.setBrandId(Objects.equals(newBrandId, globalBrandId) ? null : newBrandId);
      }
      cleared.remove("brand");
    } else if (Boolean.TRUE.equals(input.clearBrand())) {
      edit.setBrandId(null);
      setCleared(cleared, "brand", product.getBrand() != null || product.getExternalBrandName() != null);
    }

    if (input.categoryId() != null) {
      Category category = categoryRepository.findById(input.categoryId())
          .orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));
      Long effectiveCategoryId = product.getCategory() == null ? null : product.getCategory().getId();
      edit.setCategoryId(category.getId().equals(effectiveCategoryId) ? null : category.getId());
    }

    // Gramáž/objem se přepočítávají společně (unitBase/netContentValue/netContentUom/
    // isVariableWeight na sobě závisí) — NetContentCalculator musí dostat VÝSLEDNÝ stav
    // (patch + nezměněná zbylá pole), ne jen to, co uživatel právě poslal.
    UnitBase effectiveUnitBase = input.unitBase() != null ? input.unitBase() : product.getUnitBase();
    if (input.unitBase() != null) {
      edit.setUnitBase(input.unitBase() == product.getUnitBase() ? null : input.unitBase().name());
    }
    if (input.netContentValue() != null || input.netContentUom() != null || input.isVariableWeight() != null) {
      boolean effectiveVariableWeight = input.isVariableWeight() != null
          ? input.isVariableWeight() : product.isVariableWeight();
      BigDecimal effectiveValue = input.netContentValue() != null
          ? input.netContentValue() : product.getNetContentValue();
      NetContentUom effectiveUom = input.netContentUom() != null ? input.netContentUom() : product.getNetContentUom();
      BigDecimal netContentBase = NetContentCalculator.computeNetContentBase(
          effectiveUnitBase, effectiveValue, effectiveUom, effectiveVariableWeight);

      edit.setNetContentValue(bigDecimalEquals(effectiveValue, product.getNetContentValue()) ? null : effectiveValue);
      edit.setNetContentUom(effectiveUom == product.getNetContentUom() ? null : nameOrNull(effectiveUom));
      edit.setNetContentBase(bigDecimalEquals(netContentBase, product.getNetContentBase()) ? null : netContentBase);
      edit.setVariableWeight(effectiveVariableWeight == product.isVariableWeight() ? null : effectiveVariableWeight);
    }

    if (input.piecesInPack() != null) {
      edit.setPiecesInPack(input.piecesInPack().equals(product.getPiecesInPack()) ? null : input.piecesInPack());
      cleared.remove("piecesInPack");
    } else if (Boolean.TRUE.equals(input.clearPiecesInPack())) {
      edit.setPiecesInPack(null);
      setCleared(cleared, "piecesInPack", product.getPiecesInPack() != null);
    }

    edit.setClearedFields(cleared);
    if (isProductEditEmpty(edit)) {
      existing.ifPresent(productUserEditRepository::delete);
    } else {
      // Každá další úprava vrací patch do fronty budoucího konsolidačního jobu — dřívější
      // zpracování (processed_at) se týkalo starého obsahu patche.
      edit.setProcessedAt(null);
      productUserEditRepository.save(edit);
    }

    return productOverlayService.applyOverlay(storedProduct, user.getId());
  }

  @Transactional
  public Store updateStore(Long storeId, UpdateStoreInput input, UUID viewerPublicUid) {
    AppUser user = requireUser(viewerPublicUid, ErrorCode.STORE_EDIT_REQUIRES_LOGIN);
    Store store = storeRepository.findById(storeId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));

    Optional<StoreUserEdit> existing = storeUserEditRepository.findByStoreIdAndUserId(storeId, user.getId());
    StoreUserEdit edit = existing.orElseGet(() ->
        StoreUserEdit.builder().storeId(storeId).userId(user.getId()).build());
    List<String> cleared = new ArrayList<>(edit.getClearedFields());

    if (input.name() != null) {
      String trimmed = input.name().trim();
      if (trimmed.isEmpty()) throw new ValidationException(ErrorCode.STORE_NAME_EMPTY);
      edit.setName(trimmed.equals(store.getName()) ? null : trimmed);
    }

    if (input.chainId() != null) {
      RetailChain chain = retailChainRepository.findById(input.chainId())
          .orElseThrow(() -> new NotFoundException(ErrorCode.CHAIN_NOT_FOUND));
      Long globalChainId = store.getChain() == null ? null : store.getChain().getId();
      edit.setChainId(chain.getId().equals(globalChainId) ? null : chain.getId());
      cleared.remove("chain");
    } else if (Boolean.TRUE.equals(input.clearChain())) {
      edit.setChainId(null);
      setCleared(cleared, "chain", store.getChain() != null);
    }

    if (input.street() != null) {
      String trimmed = input.street().trim();
      edit.setStreet(trimmed.equals(store.getStreet()) ? null : trimmed);
      cleared.remove("street");
    } else if (Boolean.TRUE.equals(input.clearStreet())) {
      edit.setStreet(null);
      setCleared(cleared, "street", store.getStreet() != null);
    }

    if (input.city() != null) {
      String trimmed = input.city().trim();
      if (trimmed.isEmpty()) throw new ValidationException(ErrorCode.STORE_CITY_EMPTY);
      edit.setCity(trimmed.equals(store.getCity()) ? null : trimmed);
    }

    if (input.postalCode() != null) {
      String trimmed = input.postalCode().trim();
      edit.setPostalCode(trimmed.equals(store.getPostalCode()) ? null : trimmed);
      cleared.remove("postalCode");
    } else if (Boolean.TRUE.equals(input.clearPostalCode())) {
      edit.setPostalCode(null);
      setCleared(cleared, "postalCode", store.getPostalCode() != null);
    }

    // Country na rozdíl od zbytku téhle metody NEJDE do store_user_edit — má tvrdý dopad na
    // měnu zápisu (CurrencyResolver.forStore) a validaci IČO/NIP pro VŠECHNY uživatele, ne jen
    // na to, jak provozovnu vidí autor patche. Zapisuje se proto rovnou do spravované entity
    // (StoreUserEdit, docs/lokalizace.md, "Country selector v UI") a vyžaduje vyšší důvěru než
    // obyčejné přihlášení, aby si obchod nemohl "přebarvit" kdokoliv jedním klikem.
    if (input.country() != null) {
      String trimmed = input.country().trim();
      if (!countryResolver.isSupported(trimmed)) {
        throw new ValidationException(ErrorCode.UNSUPPORTED_COUNTRY);
      }
      if (!trimmed.equals(store.getCountry())) {
        if (!trustLevelService.isTrusted(user)) {
          throw new ValidationException(ErrorCode.STORE_COUNTRY_EDIT_REQUIRES_TRUST);
        }
        store.setCountry(trimmed);
        storeRepository.save(store);
      }
    }

    if (input.ico() != null) {
      String trimmed = input.ico().trim();
      // store.getCountry() je tu už po případném přímém zápisu výš, takže odráží efektivní zemi.
      companyIdValidators.forCountry(store.getCountry()).ifPresent(validator -> {
        if (!validator.isValid(trimmed)) {
          throw new ValidationException(ErrorCode.COMPANY_ID_INVALID);
        }
      });
      edit.setIco(trimmed.equals(store.getIco()) ? null : trimmed);
      cleared.remove("ico");
    } else if (Boolean.TRUE.equals(input.clearIco())) {
      edit.setIco(null);
      setCleared(cleared, "ico", store.getIco() != null);
    }

    if (input.lat() != null) {
      edit.setLat(bigDecimalEquals(input.lat(), store.getLat()) ? null : input.lat());
    }
    if (input.lon() != null) {
      edit.setLon(bigDecimalEquals(input.lon(), store.getLon()) ? null : input.lon());
    }
    if (input.geoSource() != null) {
      edit.setGeoSource(input.geoSource() == store.getGeoSource() ? null : input.geoSource().name());
    }
    if (input.osmRef() != null) {
      edit.setOsmRef(input.osmRef().equals(store.getOsmRef()) ? null : input.osmRef());
    }

    if (input.url() != null) {
      String trimmed = input.url().trim();
      if (!UrlValidation.isValidStoreUrl(trimmed)) {
        throw new ValidationException(ErrorCode.STORE_URL_INVALID);
      }
      edit.setUrl(trimmed.equals(store.getUrl()) ? null : trimmed);
      cleared.remove("url");
    } else if (Boolean.TRUE.equals(input.clearUrl())) {
      edit.setUrl(null);
      setCleared(cleared, "url", store.getUrl() != null);
    }

    edit.setClearedFields(cleared);
    if (isStoreEditEmpty(edit)) {
      existing.ifPresent(storeUserEditRepository::delete);
    } else {
      edit.setProcessedAt(null);
      storeUserEditRepository.save(edit);
    }

    return storeOverlayService.applyOverlay(store, user.getId());
  }

  /** OFF snapshot podle primárního GTINu — týž výběr jako v {@link ProductOverlayService}. */
  private OffProduct offSnapshotFor(Long productId) {
    return productCodeRepository.findByProductId(productId).stream()
        .filter(code -> code.getCodeType() == CodeType.GTIN)
        .sorted(Comparator.comparing(ProductCode::isPrimary).reversed())
        .map(ProductCode::getCode).map(offProductRepository::findById)
        .flatMap(Optional::stream)
        .filter(off -> off.getFetchStatus() == OffFetchStatus.FOUND).findFirst().orElse(null);
  }

  private AppUser requireUser(UUID viewerPublicUid, ErrorCode requiresLoginCode) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(requiresLoginCode);
    }
    return appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
  }

  /** Přidá/odebere pole ze seznamu vymazaných podle toho, jestli globální hodnota vůbec byla čím mazat. */
  private void setCleared(List<String> cleared, String field, boolean globalHadValue) {
    cleared.remove(field);
    if (globalHadValue) cleared.add(field);
  }

  private boolean isProductEditEmpty(ProductUserEdit edit) {
    return edit.getName() == null && edit.getBrandId() == null && edit.getCategoryId() == null
        && edit.getUnitBase() == null && edit.getNetContentValue() == null && edit.getNetContentUom() == null
        && edit.getNetContentBase() == null && edit.getPiecesInPack() == null && edit.getVariableWeight() == null
        && edit.getClearedFields().isEmpty();
  }

  private boolean isStoreEditEmpty(StoreUserEdit edit) {
    return edit.getName() == null && edit.getChainId() == null && edit.getStreet() == null
        && edit.getCity() == null && edit.getPostalCode() == null
        && edit.getIco() == null && edit.getLat() == null && edit.getLon() == null
        && edit.getGeoSource() == null && edit.getOsmRef() == null && edit.getUrl() == null
        && edit.getClearedFields().isEmpty();
  }

  private boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) return a == b;
    return a.compareTo(b) == 0;
  }

  private String nameOrNull(NetContentUom uom) {
    return uom == null ? null : uom.name();
  }
}
