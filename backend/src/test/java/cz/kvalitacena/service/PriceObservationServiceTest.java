package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.SubmitObservationsInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.AppException;
import cz.kvalitacena.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link PriceObservationService#submit} zapisuje DÁVKU cen z jedné cenovky v jedné transakci
 * (docs/datovy-model.md) — kolize jediného druhu ceny musí shodit celou dávku, ne zapsat jen
 * část. Testuje se přes veřejný vstupní bod, stejný vzorec jako PriceAggregationServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class PriceObservationServiceTest {

  private static final Long PRODUCT_ID = 1L;
  private static final Long STORE_ID = 2L;
  private static final Long SUBMITTER_ID = 3L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private PriceAggregationService priceAggregationService;
  @Mock
  private ProductCatalogService productCatalogService;
  @Mock
  private ProductOverlayService productOverlayService;
  @Mock
  private StoreService storeService;
  @Mock
  private CurrencyResolver currencyResolver;
  @Mock
  private EntityManager entityManager;

  @Captor
  private ArgumentCaptor<List<PriceObservation>> savedCaptor;

  private PriceObservationService service;
  private Product product;
  private Store store;
  private AppUser submitter;

  @BeforeEach
  void setUp() {
    service = new PriceObservationService(productRepository, storeRepository, appUserRepository,
        priceObservationRepository, priceAggregationService, productCatalogService, productOverlayService, storeService,
        currencyResolver, new ProductScopeService(), entityManager);

    product = Product.builder().id(PRODUCT_ID).status(ProductStatus.ACTIVE)
        .netContentBase(BigDecimal.ONE).build();
    store = Store.builder().id(STORE_ID).status(StoreStatus.ACTIVE).country("CZ").build();
    submitter = AppUser.builder().id(SUBMITTER_ID).publicUid(PUBLIC_UID).observationCount(0).build();

    lenient().when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    lenient().when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    lenient().when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(submitter));
    lenient().when(productOverlayService.applyOverlay(any(Product.class), any())).thenReturn(product);
    lenient().when(currencyResolver.forStore(store)).thenReturn("CZK");
    lenient().when(priceObservationRepository.saveAllAndFlush(anyList()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static ObservationPriceInput price(PriceKind kind, String amount) {
    return new ObservationPriceInput(kind, amount == null ? null : new BigDecimal(amount), null, null, null, null);
  }

  private static ObservationPriceInput multibuy(int qty, String total) {
    return new ObservationPriceInput(PriceKind.MULTIBUY, null, qty, new BigDecimal(total), null, null);
  }

  private static ObservationPriceInput promo(String amount, LocalDate validFrom, LocalDate validTo) {
    return new ObservationPriceInput(PriceKind.PROMO, new BigDecimal(amount), null, null, validFrom, validTo);
  }

  private static SubmitObservationsInput input(List<ObservationPriceInput> prices) {
    return new SubmitObservationsInput(PRODUCT_ID, STORE_ID, null, null, null, prices);
  }

  private static SubmitObservationsInput inputWithBasis(QuantityBasis basis, List<ObservationPriceInput> prices) {
    return new SubmitObservationsInput(PRODUCT_ID, STORE_ID, basis, null, null, prices);
  }

  private AppException submitAndCaptureError(SubmitObservationsInput submitInput) {
    return catchAppException(() -> service.submit(submitInput, PUBLIC_UID, ObservationSource.WEB));
  }

  private static AppException catchAppException(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected AppException, none was thrown");
    } catch (AppException e) {
      return e;
    }
  }

  @Test
  void singlePriceIsStoredAndEnqueuedOnce() {
    List<PriceObservation> result = service.submit(input(List.of(price(PriceKind.REGULAR, "29.90"))),
        PUBLIC_UID, ObservationSource.WEB);

    assertThat(result).hasSize(1);
    verify(priceAggregationService, times(1)).enqueueRecompute(PRODUCT_ID, STORE_ID, RecomputeReason.NEW_OBS);
  }

  @Test
  void batchStoresEveryPriceKindButEnqueuesRecomputeOnce() {
    service.submit(input(List.of(
        price(PriceKind.REGULAR, "29.90"),
        price(PriceKind.CLUB_CARD, "24.90"),
        price(PriceKind.PROMO, "19.90"))), PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).extracting(PriceObservation::getPriceKind)
        .containsExactlyInAnyOrder(PriceKind.REGULAR, PriceKind.CLUB_CARD, PriceKind.PROMO);
    verify(priceAggregationService, times(1)).enqueueRecompute(anyLong(), anyLong(), any());
  }

  @Test
  void batchIncrementsObservationCountOnlyOnce() {
    service.submit(input(List.of(
        price(PriceKind.REGULAR, "29.90"),
        price(PriceKind.CLUB_CARD, "24.90"),
        price(PriceKind.PROMO, "19.90"))), PUBLIC_UID, ObservationSource.WEB);

    assertThat(submitter.getObservationCount()).isEqualTo(1);
    verify(appUserRepository, times(1)).save(submitter);
  }

  @Test
  void promotionRunsOnceForDraftProductAndPendingStore() {
    product.setStatus(ProductStatus.DRAFT);
    store.setStatus(StoreStatus.PENDING);

    service.submit(input(List.of(
        price(PriceKind.REGULAR, "29.90"),
        price(PriceKind.CLUB_CARD, "24.90"))), PUBLIC_UID, ObservationSource.WEB);

    verify(productCatalogService, times(1)).promoteIfConfirmed(PRODUCT_ID);
    verify(storeService, times(1)).promoteIfConfirmed(STORE_ID);
  }

  @Test
  void duplicatePriceKindInsideBatchFailsBeforeAnyWrite() {
    AppException error = submitAndCaptureError(input(List.of(
        price(PriceKind.REGULAR, "29.90"),
        price(PriceKind.REGULAR, "24.90"))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_DUPLICATE_PRICE_KIND);
    assertThat(error.getArgs()).containsExactly("REGULAR");
    verifyNoInteractions(priceObservationRepository, priceAggregationService, appUserRepository);
  }

  @Test
  void duplicateReportsTheKindTheUserSeesSecond() {
    AppException error = submitAndCaptureError(input(List.of(
        price(PriceKind.CLUB_CARD, "24.90"),
        price(PriceKind.PROMO, "19.90"),
        price(PriceKind.PROMO, "18.90"))));

    assertThat(error.getArgs()).containsExactly("PROMO");
  }

  @Test
  void conflictWithTodaysStoredKindFailsWholeBatch() {
    when(priceObservationRepository.findPriceKindsBySubmitterOnDay(eq(PRODUCT_ID), eq(STORE_ID),
        eq(SUBMITTER_ID), any())).thenReturn(List.of("CLUB_CARD"));

    AppException error = submitAndCaptureError(input(List.of(
        price(PriceKind.REGULAR, "29.90"),
        price(PriceKind.CLUB_CARD, "24.90"))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY);
    assertThat(error.getArgs()).containsExactly("CLUB_CARD");
    verifyNoInteractions(priceAggregationService);
    verify(priceObservationRepository, never()).saveAllAndFlush(anyList());
    verify(appUserRepository, never()).save(any());
  }

  @Test
  void databaseConflictIsTranslatedToValidationError() {
    when(priceObservationRepository.saveAllAndFlush(anyList()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    AppException error = submitAndCaptureError(input(List.of(price(PriceKind.REGULAR, "29.90"))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_ALREADY_SUBMITTED_TODAY);
  }

  @Test
  void anonymousSubmitterSkipsTodayCheckAndCounter() {
    service.submit(input(List.of(price(PriceKind.REGULAR, "29.90"))), null, ObservationSource.WEB);

    verify(priceObservationRepository, never()).findPriceKindsBySubmitterOnDay(any(), any(), any(), any());
    verify(appUserRepository, never()).save(any());
  }

  @Test
  void multibuyMultipliesNetContentAndUsesTotalAsPrice() {
    product.setNetContentBase(new BigDecimal("0.5"));

    service.submit(input(List.of(multibuy(3, "50"))), PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    PriceObservation saved = savedCaptor.getValue().get(0);
    assertThat(saved.getNetContentBase()).isEqualByComparingTo("1.5");
    assertThat(saved.getPriceAmount()).isEqualByComparingTo("50");
  }

  @Test
  void promoValidityIsStoredOnTheObservation() {
    LocalDate from = LocalDate.now().minusDays(2);
    LocalDate to = LocalDate.now().plusDays(5);

    service.submit(input(List.of(promo("19.90", from, to))), PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    PriceObservation saved = savedCaptor.getValue().get(0);
    assertThat(saved.getPromoValidFrom()).isEqualTo(from);
    assertThat(saved.getPromoValidTo()).isEqualTo(to);
  }

  @Test
  void promoValidityOnNonPromoKindIsRejected() {
    ObservationPriceInput regularWithValidity = new ObservationPriceInput(
        PriceKind.REGULAR, new BigDecimal("29.90"), null, null, null, LocalDate.now());

    AppException error = submitAndCaptureError(input(List.of(regularWithValidity)));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED);
    assertThat(error.getArgs()).containsExactly("REGULAR");
    verifyNoInteractions(priceObservationRepository, priceAggregationService, appUserRepository);
  }

  @Test
  void promoValidFromAfterValidToIsRejected() {
    AppException error = submitAndCaptureError(input(List.of(
        promo("19.90", LocalDate.now(), LocalDate.now().minusDays(1)))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PROMO_VALIDITY_RANGE_INVALID);
    verifyNoInteractions(priceObservationRepository, priceAggregationService, appUserRepository);
  }

  @Test
  void promoValidFromInFutureIsRejected() {
    AppException error = submitAndCaptureError(input(List.of(
        promo("19.90", LocalDate.now().plusDays(1), null))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PROMO_VALID_FROM_IN_FUTURE);
    verifyNoInteractions(priceObservationRepository, priceAggregationService, appUserRepository);
  }

  @Test
  void multibuyRowDoesNotAffectNetContentOfOtherRows() {
    product.setNetContentBase(new BigDecimal("0.5"));

    service.submit(input(List.of(price(PriceKind.REGULAR, "29.90"), multibuy(3, "50"))),
        PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    PriceObservation regular = savedCaptor.getValue().stream()
        .filter(o -> o.getPriceKind() == PriceKind.REGULAR).findFirst().orElseThrow();
    assertThat(regular.getNetContentBase()).isEqualByComparingTo("0.5");
  }

  @Test
  void variableWeightPerKgSnapshotsNetContentAsOneForWholeBatch() {
    product.setNetContentBase(new BigDecimal("0.5")); // nesmí se použít, basis je PER_KG

    service.submit(inputWithBasis(QuantityBasis.PER_KG, List.of(
        price(PriceKind.REGULAR, "29.90"), price(PriceKind.CLUB_CARD, "24.90"))),
        PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).extracting(PriceObservation::getNetContentBase)
        .allSatisfy(v -> assertThat(v).isEqualByComparingTo("1"));
  }

  @Test
  void emptyPriceListIsRejected() {
    AppException error = submitAndCaptureError(input(List.of()));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PRICES_REQUIRED);
    verifyNoInteractions(productRepository, storeRepository, priceObservationRepository);
  }

  @Test
  void storeScopedProductCannotBeReportedAtAnotherStore() {
    product.setCatalogScope(ProductScope.STORE);
    product.setScopeStore(Store.builder().id(99L).build());

    AppException error = submitAndCaptureError(input(List.of(price(PriceKind.REGULAR, "29.90"))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE_AT_STORE);
    verifyNoInteractions(priceObservationRepository);
  }

  @Test
  void missingPriceAmountIsRejected() {
    AppException error = submitAndCaptureError(input(List.of(price(PriceKind.REGULAR, null))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PRICE_INCOMPLETE);
    assertThat(error.getArgs()).containsExactly("REGULAR");
  }

  @Test
  void multibuyWithoutQtyOrTotalIsRejected() {
    AppException error = submitAndCaptureError(
        input(List.of(new ObservationPriceInput(PriceKind.MULTIBUY, null, null, null, null, null))));

    assertThat(error.getCode()).isEqualTo(ErrorCode.OBSERVATION_PRICE_INCOMPLETE);
    assertThat(error.getArgs()).containsExactly("MULTIBUY");
  }

  @Test
  void unsupportedClientCurrencyFallsBackToStoreCurrencyForWholeBatch() {
    when(currencyResolver.isSupported("XYZ")).thenReturn(false);
    SubmitObservationsInput unsupportedCurrencyInput =
        new SubmitObservationsInput(PRODUCT_ID, STORE_ID, null, null, "XYZ",
            List.of(price(PriceKind.REGULAR, "29.90"), price(PriceKind.CLUB_CARD, "24.90")));

    service.submit(unsupportedCurrencyInput, PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).extracting(PriceObservation::getCurrency)
        .containsOnly("CZK");
  }

  @Test
  void observedAtIsResolvedOnceForWholeBatch() {
    OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-01T09:00:00Z");
    SubmitObservationsInput submitInput = new SubmitObservationsInput(PRODUCT_ID, STORE_ID, null, observedAt, null,
        List.of(price(PriceKind.REGULAR, "29.90"), price(PriceKind.CLUB_CARD, "24.90")));

    service.submit(submitInput, PUBLIC_UID, ObservationSource.WEB);

    verify(priceObservationRepository).saveAllAndFlush(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).extracting(PriceObservation::getObservedAt)
        .containsOnly(observedAt);
  }

  private static <T> List<T> anyList() {
    return any();
  }
}
