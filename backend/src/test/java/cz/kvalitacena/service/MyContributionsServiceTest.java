package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.MyEditItem;
import cz.kvalitacena.controller.MyObservationItem;
import cz.kvalitacena.controller.MyProductItem;
import cz.kvalitacena.controller.MyStoreItem;
import cz.kvalitacena.controller.PublicationState;
import cz.kvalitacena.db.entity.GeoSource;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty") — hlídá
 * hlavně odvození {@link cz.kvalitacena.controller.PublicationStatus}: konkrétní čísla
 * u AWAITING_CONFIRMATIONS, prioritu HIDDEN_AFTER_FLAGS nad AWAITING_CONFIRMATIONS u
 * odvozeného stavu ceny, a changedFields u úprav cizích záznamů. Leave-one-out počítání
 * samotné je v nativním SQL (PriceObservationRepository) — testuje ho jen chování služby nad
 * tím, co repozitář vrátí, stejně jako zbytek projektu testuje service vrstvu přes Mockito.
 */
@ExtendWith(MockitoExtension.class)
class MyContributionsServiceTest {

  private static final Long USER_ID = 42L;

  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private ProductUserEditRepository productUserEditRepository;
  @Mock
  private StoreUserEditRepository storeUserEditRepository;
  @Mock
  private ProductReviewRepository productReviewRepository;
  @Mock
  private ProductOverlayService productOverlayService;
  @Mock
  private StoreOverlayService storeOverlayService;
  @Mock
  private FxRateService fxRateService;

  private final CatalogProperties catalogProperties = new CatalogProperties();

  private MyContributionsService service() {
    catalogProperties.setDraftConfirmations(3);
    return new MyContributionsService(productRepository, storeRepository, priceObservationRepository,
        productUserEditRepository, storeUserEditRepository, productReviewRepository, productOverlayService,
        storeOverlayService, catalogProperties, fxRateService);
  }

  private void passthroughOverlays() {
    lenient().when(productOverlayService.applyOverlay(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(storeOverlayService.applyOverlay(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private Product product(Long id, ProductStatus status, OffsetDateTime hiddenAt) {
    return Product.builder().id(id).name("Chléb konzumní").status(status).hiddenAt(hiddenAt)
        .createdByUserId(USER_ID).createdAt(OffsetDateTime.now()).build();
  }

  private Store store(Long id, StoreStatus status, OffsetDateTime hiddenAt) {
    return Store.builder().id(id).name("Albert").city("Brno").status(status).hiddenAt(hiddenAt)
        .createdByUserId(USER_ID).createdAt(OffsetDateTime.now()).build();
  }

  @Test
  void draftProductReportsExactConfirmationCount() {
    passthroughOverlays();
    Product draft = product(1L, ProductStatus.DRAFT, null);
    when(productRepository.findByCreatedByUserId(USER_ID, 20, 0)).thenReturn(List.of(draft));
    when(productRepository.countByCreatedByUserId(USER_ID)).thenReturn(1L);
    when(priceObservationRepository.countDistinctProductContributorsExcludingBatch(List.of(1L)))
        .thenReturn(List.of(contributorCount(1L, 1)));

    List<MyProductItem> items = service().myProducts(USER_ID, null, null).items();

    assertThat(items).hasSize(1);
    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.AWAITING_CONFIRMATIONS);
    assertThat(items.get(0).publication().confirmationsReceived()).isEqualTo(1);
    assertThat(items.get(0).publication().confirmationsRequired()).isEqualTo(3);
  }

  @Test
  void hiddenProductWinsOverDraftStatus() {
    passthroughOverlays();
    Product hidden = product(2L, ProductStatus.DRAFT, OffsetDateTime.now());
    when(productRepository.findByCreatedByUserId(USER_ID, 20, 0)).thenReturn(List.of(hidden));
    when(productRepository.countByCreatedByUserId(USER_ID)).thenReturn(1L);

    List<MyProductItem> items = service().myProducts(USER_ID, null, null).items();

    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.HIDDEN_AFTER_FLAGS);
    assertThat(items.get(0).publication().confirmationsReceived()).isNull();
  }

  @Test
  void activeProductIsPublic() {
    passthroughOverlays();
    Product active = product(3L, ProductStatus.ACTIVE, null);
    when(productRepository.findByCreatedByUserId(USER_ID, 20, 0)).thenReturn(List.of(active));
    when(productRepository.countByCreatedByUserId(USER_ID)).thenReturn(1L);

    List<MyProductItem> items = service().myProducts(USER_ID, null, null).items();

    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.PUBLIC);
    assertThat(items.get(0).publication().verified()).isFalse(); // konsolidační job v etapě 1 neběží
  }

  @Test
  void pendingStoreReportsExactConfirmationCount() {
    passthroughOverlays();
    Store pending = store(4L, StoreStatus.PENDING, null);
    when(storeRepository.findByCreatedByUserId(USER_ID, 20, 0)).thenReturn(List.of(pending));
    when(storeRepository.countByCreatedByUserId(USER_ID)).thenReturn(1L);
    when(priceObservationRepository.countDistinctContributorsExcludingBatch(List.of(4L)))
        .thenReturn(List.of(contributorCount(4L, 0)));

    List<MyStoreItem> items = service().myStores(USER_ID, null, null).items();

    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.AWAITING_CONFIRMATIONS);
    assertThat(items.get(0).publication().confirmationsReceived()).isEqualTo(0);
    assertThat(items.get(0).publication().confirmationsRequired()).isEqualTo(3);
  }

  /** Cena od blokujícího zboží I obchodu dědí HORŠÍ z obou stavů — HIDDEN vyhrává nad AWAITING. */
  @Test
  void observationInheritsWorseOfProductAndStoreStatus() {
    passthroughOverlays();
    Product hiddenProduct = product(5L, ProductStatus.DRAFT, OffsetDateTime.now());
    Store draftStore = store(6L, StoreStatus.PENDING, null);
    PriceObservation observation = PriceObservation.builder()
        .id(100L).product(hiddenProduct).store(draftStore)
        .priceKind(PriceKind.REGULAR).priceAmount(new BigDecimal("29.90")).currency("CZK")
        .observedAt(OffsetDateTime.now()).createdAt(OffsetDateTime.now()).build();

    when(priceObservationRepository.findBySubmitterId(USER_ID, 20, 0)).thenReturn(List.of(observation));
    when(priceObservationRepository.countBySubmitterId(USER_ID)).thenReturn(1L);
    when(productRepository.findAllById(List.of(5L))).thenReturn(List.of(hiddenProduct));
    when(storeRepository.findAllById(List.of(6L))).thenReturn(List.of(draftStore));
    when(priceObservationRepository.countDistinctContributorsExcludingBatch(List.of(6L)))
        .thenReturn(List.of(contributorCount(6L, 2)));

    List<MyObservationItem> items = service().myObservations(USER_ID, null, null, null).items();

    assertThat(items).hasSize(1);
    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.HIDDEN_AFTER_FLAGS);
  }

  @Test
  void productEditReportsChangedFieldsIncludingClearedOnes() {
    passthroughOverlays();
    Product product = product(7L, ProductStatus.ACTIVE, null);
    ProductUserEdit edit = ProductUserEdit.builder()
        .productId(7L).userId(USER_ID).name("Nový název")
        .clearedFields(List.of("brand"))
        .updatedAt(OffsetDateTime.now()).build();
    when(productUserEditRepository.findByUserId(USER_ID)).thenReturn(List.of(edit));
    when(storeUserEditRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(productRepository.findAllById(List.of(7L))).thenReturn(List.of(product));

    List<MyEditItem> items = service().myEdits(USER_ID, null, null).items();

    assertThat(items).hasSize(1);
    assertThat(items.get(0).recordType()).isEqualTo(RecordType.PRODUCT);
    assertThat(items.get(0).changedFields()).containsExactlyInAnyOrder("name", "brand");
    assertThat(items.get(0).publication().state()).isEqualTo(PublicationState.PENDING_MERGE);
  }

  @Test
  void storeEditWithGeoChangeReportsField() {
    passthroughOverlays();
    Store store = store(8L, StoreStatus.ACTIVE, null);
    StoreUserEdit edit = StoreUserEdit.builder()
        .storeId(8L).userId(USER_ID).lat(new BigDecimal("49.195")).lon(new BigDecimal("16.608"))
        .geoSource(GeoSource.COMMUNITY.name())
        .updatedAt(OffsetDateTime.now()).build();
    when(productUserEditRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(storeUserEditRepository.findByUserId(USER_ID)).thenReturn(List.of(edit));
    when(storeRepository.findAllById(List.of(8L))).thenReturn(List.of(store));

    List<MyEditItem> items = service().myEdits(USER_ID, null, null).items();

    assertThat(items).hasSize(1);
    assertThat(items.get(0).recordType()).isEqualTo(RecordType.STORE);
    assertThat(items.get(0).changedFields()).containsExactlyInAnyOrder("lat", "lon", "geoSource");
  }

  private PriceObservationRepository.ContributorCount contributorCount(long id, long cnt) {
    return new PriceObservationRepository.ContributorCount() {
      @Override
      public Long getId() {
        return id;
      }

      @Override
      public long getCnt() {
        return cnt;
      }
    };
  }
}
