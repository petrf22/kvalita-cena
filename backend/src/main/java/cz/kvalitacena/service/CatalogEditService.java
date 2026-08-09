package cz.kvalitacena.service;

import cz.kvalitacena.controller.UpdateProductInput;
import cz.kvalitacena.controller.UpdateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Brand;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
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

  private final StoreRepository storeRepository;
  private final StoreUserEditRepository storeUserEditRepository;
  private final RetailChainRepository retailChainRepository;
  private final CompanyIdValidators companyIdValidators;
  private final StoreOverlayService storeOverlayService;

  private final AppUserRepository appUserRepository;

  @Transactional
  public Product updateProduct(Long productId, UpdateProductInput input, UUID viewerPublicUid) {
    AppUser user = requireUser(viewerPublicUid, ErrorCode.PRODUCT_EDIT_REQUIRES_LOGIN);
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));

    Optional<ProductUserEdit> existing = productUserEditRepository.findByProductIdAndUserId(productId, user.getId());
    ProductUserEdit edit = existing.orElseGet(() ->
        ProductUserEdit.builder().productId(productId).userId(user.getId()).build());
    List<String> cleared = new ArrayList<>(edit.getClearedFields());

    if (input.name() != null) {
      String trimmed = input.name().trim();
      if (trimmed.isEmpty()) throw new ValidationException(ErrorCode.PRODUCT_NAME_EMPTY);
      edit.setName(trimmed.equals(product.getName()) ? null : trimmed);
    }

    if (input.brandName() != null) {
      Brand brand = brandResolutionService.resolve(input.brandName());
      Long globalBrandId = product.getBrand() == null ? null : product.getBrand().getId();
      Long newBrandId = brand == null ? null : brand.getId();
      edit.setBrandId(Objects.equals(newBrandId, globalBrandId) ? null : newBrandId);
      cleared.remove("brand");
    } else if (Boolean.TRUE.equals(input.clearBrand())) {
      edit.setBrandId(null);
      setCleared(cleared, "brand", product.getBrand() != null);
    }

    if (input.categoryId() != null) {
      Category category = categoryRepository.findById(input.categoryId())
          .orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));
      edit.setCategoryId(category.getId().equals(product.getCategory().getId()) ? null : category.getId());
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

    return productOverlayService.applyOverlay(product, user.getId());
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

    if (input.country() != null) {
      String trimmed = input.country().trim();
      edit.setCountry(trimmed.equals(store.getCountry()) ? null : trimmed);
    }

    if (input.ico() != null) {
      String trimmed = input.ico().trim();
      // Země patchované v TÉTO úpravě má přednost před globální (uživatel může měnit obojí
      // najednou), jinak validátor podle aktuální globální země obchodu.
      String effectiveCountry = input.country() != null ? input.country().trim() : store.getCountry();
      companyIdValidators.forCountry(effectiveCountry).ifPresent(validator -> {
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

    edit.setClearedFields(cleared);
    if (isStoreEditEmpty(edit)) {
      existing.ifPresent(storeUserEditRepository::delete);
    } else {
      edit.setProcessedAt(null);
      storeUserEditRepository.save(edit);
    }

    return storeOverlayService.applyOverlay(store, user.getId());
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
        && edit.getCity() == null && edit.getPostalCode() == null && edit.getCountry() == null
        && edit.getIco() == null && edit.getLat() == null && edit.getLon() == null
        && edit.getGeoSource() == null && edit.getOsmRef() == null && edit.getClearedFields().isEmpty();
  }

  private boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) return a == b;
    return a.compareTo(b) == 0;
  }

  private String nameOrNull(NetContentUom uom) {
    return uom == null ? null : uom.name();
  }
}
