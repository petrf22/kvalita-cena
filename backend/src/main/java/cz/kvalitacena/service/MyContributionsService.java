package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.ConvertedPrice;
import cz.kvalitacena.controller.MyEditItem;
import cz.kvalitacena.controller.MyEditResult;
import cz.kvalitacena.controller.MyObservationItem;
import cz.kvalitacena.controller.MyObservationResult;
import cz.kvalitacena.controller.MyProductItem;
import cz.kvalitacena.controller.MyProductResult;
import cz.kvalitacena.controller.MyReviewItem;
import cz.kvalitacena.controller.MyReviewResult;
import cz.kvalitacena.controller.MyStoreItem;
import cz.kvalitacena.controller.MyStoreResult;
import cz.kvalitacena.controller.PublicationState;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import cz.kvalitacena.service.fx.FxRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Výpis "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty";
 * prahy v docs/reputace.md) — vlastní založené zboží/obchody, vlastní zapsané ceny a vlastní
 * úpravy cizích záznamů, každý se stavem {@link PublicationStatus}. Cílem je, aby uživatel
 * viděl KONKRÉTNÍ čísla ("zatím 1 ze 3"), ne jen štítek "čeká na potvrzení" bez kontextu —
 * jinak si komunitní appka bez viditelného pokroku snadno vyloží jako nefunkční.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyContributionsService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;

  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final ProductUserEditRepository productUserEditRepository;
  private final StoreUserEditRepository storeUserEditRepository;
  private final ProductReviewRepository productReviewRepository;
  private final ProductOverlayService productOverlayService;
  private final StoreOverlayService storeOverlayService;
  private final CatalogProperties catalogProperties;
  private final FxRateService fxRateService;

  public MyProductResult myProducts(Long userId, Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<Product> page = productOverlayService.applyOverlay(
        productRepository.findByCreatedByUserId(userId, limit, off), userId);
    long total = productRepository.countByCreatedByUserId(userId);

    int required = catalogProperties.getDraftConfirmations();
    Map<Long, Long> confirmations = confirmationsForProducts(page);
    List<MyProductItem> items = page.stream()
        .map(p -> new MyProductItem(p, p.getCreatedAt(),
            productStatus(p, confirmations.getOrDefault(p.getId(), 0L), required)))
        .toList();
    return new MyProductResult(items, (int) total, off + items.size() < total);
  }

  public MyStoreResult myStores(Long userId, Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<Store> page = storeOverlayService.applyOverlay(
        storeRepository.findByCreatedByUserId(userId, limit, off), userId);
    long total = storeRepository.countByCreatedByUserId(userId);

    int required = catalogProperties.getDraftConfirmations();
    Map<Long, Long> confirmations = confirmationsForStores(page);
    List<MyStoreItem> items = page.stream()
        .map(s -> new MyStoreItem(s, s.getCreatedAt(),
            storeStatus(s, confirmations.getOrDefault(s.getId(), 0L), required)))
        .toList();
    return new MyStoreResult(items, (int) total, off + items.size() < total);
  }

  /**
   * Stav se dědí od BLOKUJÍCÍHO katalogového záznamu (zboží NEBO obchod, cokoli z obou brání
   * zveřejnění) — samotná cena žádný práh nemá, čeká jen na frontu přepočtu
   * (PriceAggregationService, do pár sekund). Pořadí HIDDEN_AFTER_FLAGS >
   * AWAITING_CONFIRMATIONS > PUBLIC odpovídá tomu, co uživatele nejvíc zajímá vidět první.
   */
  public MyObservationResult myObservations(Long userId, Integer first, Integer offset,
      String displayCurrency) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<PriceObservation> page = priceObservationRepository.findBySubmitterId(userId, limit, off);
    long total = priceObservationRepository.countBySubmitterId(userId);

    // getProduct()/getStore() na entitě z native query je LAZY proxy — getId() ji nenačte,
    // takže dávkové findAllById zůstává jediným dotazem na produkty a jediným na obchody.
    List<Long> productIds = page.stream().map(o -> o.getProduct().getId()).distinct().toList();
    List<Long> storeIds = page.stream().map(o -> o.getStore().getId()).distinct().toList();
    List<Product> products = productOverlayService.applyOverlay(productRepository.findAllById(productIds), userId);
    List<Store> stores = storeOverlayService.applyOverlay(storeRepository.findAllById(storeIds), userId);
    Map<Long, Product> productsById = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<Long, Store> storesById = stores.stream().collect(Collectors.toMap(Store::getId, Function.identity()));

    int required = catalogProperties.getDraftConfirmations();
    Map<Long, Long> productConfirmations = confirmationsForProducts(products);
    Map<Long, Long> storeConfirmations = confirmationsForStores(stores);

    List<MyObservationItem> items = page.stream()
        .map(o -> toObservationItem(o, productsById, storesById, productConfirmations, storeConfirmations,
            required, displayCurrency))
        .toList();
    return new MyObservationResult(items, (int) total, off + items.size() < total);
  }

  public MyEditResult myEdits(Long userId, Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    // Objem je "vlastní úpravy jednoho člověka", ne celý katalog — proto se obě tabulky
    // patchů, na rozdíl od zbytku téhle služby, nesou celé a slučují/stránkují v paměti
    // (stejná úvaha jako u GDPR exportu, který ProductUserEditRepository.findByUserId taky
    // používá nestránkovaně).
    List<ProductUserEdit> productEdits = productUserEditRepository.findByUserId(userId);
    List<StoreUserEdit> storeEdits = storeUserEditRepository.findByUserId(userId);
    long total = (long) productEdits.size() + storeEdits.size();

    Map<Long, Product> productsById = productOverlayService
        .applyOverlay(productRepository.findAllById(productEdits.stream().map(ProductUserEdit::getProductId).toList()), userId)
        .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<Long, Store> storesById = storeOverlayService
        .applyOverlay(storeRepository.findAllById(storeEdits.stream().map(StoreUserEdit::getStoreId).toList()), userId)
        .stream().collect(Collectors.toMap(Store::getId, Function.identity()));

    List<MyEditItem> merged = new ArrayList<>();
    for (ProductUserEdit edit : productEdits) {
      Product product = productsById.get(edit.getProductId());
      if (product == null) continue; // produkt mezitím smazán/sloučen
      merged.add(new MyEditItem(RecordType.PRODUCT, product, null, edit.getUpdatedAt(),
          changedFields(edit), PublicationStatus.pendingMerge()));
    }
    for (StoreUserEdit edit : storeEdits) {
      Store store = storesById.get(edit.getStoreId());
      if (store == null) continue;
      merged.add(new MyEditItem(RecordType.STORE, null, store, edit.getUpdatedAt(),
          changedFields(edit), PublicationStatus.pendingMerge()));
    }
    merged.sort(Comparator.comparing(MyEditItem::updatedAt).reversed());

    List<MyEditItem> page = merged.stream().skip(off).limit(limit).toList();
    return new MyEditResult(page, (int) total, off + page.size() < total);
  }

  /**
   * Vlastní recenze s textem, nejnovější první — na rozdíl od {@code ProductReviewService
   * .reviewsFor} (co vidí VIEWER pod zbožím) vrací i vlastní recenze skryté moderací, ať autor
   * ví, že a proč zmizela (docs/reputace.md, "Moderace").
   */
  public MyReviewResult myReviews(Long userId, Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<ProductReview> page = productReviewRepository.findTextsByUser(userId, limit, off);
    long total = productReviewRepository.countByUserIdAndTextIsNotNull(userId);

    Map<Long, Product> productsById = productOverlayService
        .applyOverlay(productRepository.findAllById(page.stream().map(ProductReview::getProductId).distinct().toList()), userId)
        .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

    List<MyReviewItem> items = new ArrayList<>();
    for (ProductReview review : page) {
      Product product = productsById.get(review.getProductId());
      if (product == null) continue; // produkt mezitím smazán/sloučen
      items.add(new MyReviewItem(product, review.getStars(), review.getText(), review.getCreatedAt(),
          review.getTextUpdatedAt(), review.isHidden()));
    }
    return new MyReviewResult(items, (int) total, off + items.size() < total);
  }

  private PublicationStatus productStatus(Product product, long confirmationsReceived, int required) {
    if (product.getHiddenAt() != null) return PublicationStatus.hiddenAfterFlags();
    if (product.getStatus() == ProductStatus.DRAFT) {
      return PublicationStatus.awaitingConfirmations((int) confirmationsReceived, required);
    }
    return PublicationStatus.publicState(product.isVerified());
  }

  private PublicationStatus storeStatus(Store store, long confirmationsReceived, int required) {
    if (store.getHiddenAt() != null) return PublicationStatus.hiddenAfterFlags();
    if (store.getStatus() == StoreStatus.PENDING) {
      return PublicationStatus.awaitingConfirmations((int) confirmationsReceived, required);
    }
    return PublicationStatus.publicState(store.isVerified());
  }

  /**
   * Leave-one-out vůči SKUTEČNÉMU autorovi produktu (JOIN v repozitáři), ne vůči vieweru —
   * {@code myObservations} může listovat i cizí zboží (viewer u něj jen zapsal cenu), takže
   * "vyloučit vieweru" a "vyloučit autora" tam nejsou totéž (u {@code myProducts} ano, protože
   * tam je viewer vždy autor).
   */
  private Map<Long, Long> confirmationsForProducts(List<Product> products) {
    List<Long> draftIds = products.stream()
        .filter(p -> p.getStatus() == ProductStatus.DRAFT)
        .map(Product::getId)
        .toList();
    if (draftIds.isEmpty()) return Map.of();
    return priceObservationRepository.countDistinctProductContributorsExcludingBatch(draftIds).stream()
        .collect(Collectors.toMap(PriceObservationRepository.ContributorCount::getId,
            PriceObservationRepository.ContributorCount::getCnt));
  }

  /** Leave-one-out vůči skutečnému autorovi obchodu — viz {@link #confirmationsForProducts}. */
  private Map<Long, Long> confirmationsForStores(List<Store> stores) {
    List<Long> pendingIds = stores.stream()
        .filter(s -> s.getStatus() == StoreStatus.PENDING)
        .map(Store::getId)
        .toList();
    if (pendingIds.isEmpty()) return Map.of();
    return priceObservationRepository.countDistinctContributorsExcludingBatch(pendingIds).stream()
        .collect(Collectors.toMap(PriceObservationRepository.ContributorCount::getId,
            PriceObservationRepository.ContributorCount::getCnt));
  }

  private MyObservationItem toObservationItem(PriceObservation observation, Map<Long, Product> productsById,
      Map<Long, Store> storesById, Map<Long, Long> productConfirmations, Map<Long, Long> storeConfirmations,
      int required, String displayCurrency) {
    Product product = productsById.get(observation.getProduct().getId());
    Store store = storesById.get(observation.getStore().getId());
    PublicationStatus productBlock = productStatus(product, productConfirmations.getOrDefault(product.getId(), 0L), required);
    PublicationStatus storeBlock = storeStatus(store, storeConfirmations.getOrDefault(store.getId(), 0L), required);
    // Skryté vyhrává nad čekajícím na potvrzení, obojí vyhrává nad veřejným — cena je vidět
    // ostatním, jen když JAK zboží, TAK obchod jsou PUBLIC.
    PublicationStatus publication = blockingStatus(productBlock, storeBlock);

    ConvertedPrice converted = displayCurrency == null ? null
        : ConvertedPrice.from(fxRateService.convert(observation.getPriceAmount(), observation.getCurrency(),
            displayCurrency, observation.getObservedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate()));

    return new MyObservationItem(product, store, observation.getPriceKind(), observation.getPriceAmount(),
        observation.getUnitPrice(), observation.getCurrency(), converted,
        observation.getPromoValidFrom(), observation.getPromoValidTo(), observation.getObservedAt(),
        observation.getCreatedAt(), publication);
  }

  private PublicationStatus blockingStatus(PublicationStatus a, PublicationStatus b) {
    for (PublicationState state : List.of(PublicationState.HIDDEN_AFTER_FLAGS, PublicationState.AWAITING_CONFIRMATIONS)) {
      if (a.state() == state) return a;
      if (b.state() == state) return b;
    }
    return a; // obojí PUBLIC (myObservations nikdy nepracuje s PENDING_MERGE)
  }

  private List<String> changedFields(ProductUserEdit edit) {
    List<String> fields = new ArrayList<>();
    if (edit.getName() != null) fields.add("name");
    if (edit.getBrandId() != null || edit.getClearedFields().contains("brand")) fields.add("brand");
    if (edit.getCategoryId() != null) fields.add("category");
    if (edit.getUnitBase() != null) fields.add("unitBase");
    if (edit.getNetContentValue() != null) fields.add("netContentValue");
    if (edit.getNetContentUom() != null) fields.add("netContentUom");
    if (edit.getNetContentBase() != null) fields.add("netContentBase");
    if (edit.getPiecesInPack() != null) fields.add("piecesInPack");
    if (edit.getVariableWeight() != null) fields.add("isVariableWeight");
    return fields;
  }

  private List<String> changedFields(StoreUserEdit edit) {
    List<String> fields = new ArrayList<>();
    if (edit.getName() != null) fields.add("name");
    if (edit.getChainId() != null || edit.getClearedFields().contains("chain")) fields.add("chain");
    if (edit.getStreet() != null || edit.getClearedFields().contains("street")) fields.add("street");
    if (edit.getCity() != null) fields.add("city");
    if (edit.getPostalCode() != null || edit.getClearedFields().contains("postalCode")) fields.add("postalCode");
    if (edit.getIco() != null || edit.getClearedFields().contains("ico")) fields.add("ico");
    if (edit.getLat() != null) fields.add("lat");
    if (edit.getLon() != null) fields.add("lon");
    if (edit.getGeoSource() != null) fields.add("geoSource");
    if (edit.getOsmRef() != null) fields.add("osmRef");
    if (edit.getUrl() != null || edit.getClearedFields().contains("url")) fields.add("url");
    return fields;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
