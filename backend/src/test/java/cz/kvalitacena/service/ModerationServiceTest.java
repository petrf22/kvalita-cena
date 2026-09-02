package cz.kvalitacena.service;

import cz.kvalitacena.controller.FlaggedRecordItem;
import cz.kvalitacena.controller.FlaggedRecordResult;
import cz.kvalitacena.controller.ModerationObservationResult;
import cz.kvalitacena.controller.PublicationState;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.FlagResolution;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.RecomputeReason;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.RevokeReason;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.db.repo.RecordFlagRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nástroj pro T4 (docs/reputace.md, "Moderace") — DISMISSED je jediná cesta, jak vrátit
 * hidden_at na NULL (RecordFlagService.hideRecord byl dřív jednosměrný); zamítnutí ceny musí
 * vždy zařadit dotčenou buňku do agg.recompute_queue, jinak zůstane v grafu ještě několik dní.
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

  private static final Long PRODUCT_ID = 7L;
  private static final Long MODERATOR_ID = 1L;

  @Mock
  private RecordFlagRepository recordFlagRepository;
  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private MediaRepository mediaRepository;
  @Mock
  private ProductReviewRepository productReviewRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private ProductOverlayService productOverlayService;
  @Mock
  private StoreOverlayService storeOverlayService;
  @Mock
  private MediaService mediaService;
  @Mock
  private HandleRenderer handleRenderer;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private PriceAggregationService priceAggregationService;
  @Mock
  private RefreshTokenService refreshTokenService;
  @Mock
  private ProductCatalogService productCatalogService;

  private ModerationService service;

  @BeforeEach
  void setUp() {
    service = new ModerationService(recordFlagRepository, productRepository, storeRepository, mediaRepository,
        productReviewRepository, appUserRepository, productOverlayService, storeOverlayService, mediaService,
        handleRenderer, priceObservationRepository, priceAggregationService, refreshTokenService, productCatalogService);
    // applyOverlay(list, viewerId) je no-op, pokud moderátor sám žádný patch nemá — testy
    // ověřují mapování fronty, ne overlay logiku (ta má vlastní testy u Product/StoreOverlayService).
    lenient().when(productOverlayService.applyOverlay(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(storeOverlayService.applyOverlay(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
  }

  // --- flaggedRecords ---

  @Test
  void flaggedRecordsMapsGroupToItemWithAuthorAndSplitReasons() {
    RecordFlagRepository.FlaggedGroup group = flaggedGroup("PRODUCT", PRODUCT_ID, 3L,
        "cena neexistuje" + "\u001F" + "duplicitní zboží");
    when(recordFlagRepository.findUnresolvedGroups(null, 20, 0)).thenReturn(List.of(group));
    when(recordFlagRepository.countUnresolvedGroups(null)).thenReturn(1L);

    Long authorId = 42L;
    Product product = Product.builder().id(PRODUCT_ID).createdByUserId(authorId).build();
    when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(product));
    when(storeRepository.findAllById(List.of())).thenReturn(List.of());
    when(mediaRepository.findAllById(List.of())).thenReturn(List.of());

    UUID authorUid = UUID.randomUUID();
    AppUser author = AppUser.builder().id(authorId).publicUid(authorUid).build();
    when(appUserRepository.findAllById(any())).thenReturn(List.of(author));
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    FlaggedRecordResult result = service.flaggedRecords(null, null, null, MODERATOR_ID);

    assertThat(result.totalCount()).isEqualTo(1);
    assertThat(result.items()).hasSize(1);
    FlaggedRecordItem item = result.items().get(0);
    assertThat(item.recordType()).isEqualTo(RecordType.PRODUCT);
    assertThat(item.recordId()).isEqualTo(PRODUCT_ID);
    assertThat(item.flagCount()).isEqualTo(3);
    assertThat(item.reasons()).containsExactly("cena neexistuje", "duplicitní zboží");
    assertThat(item.authorPublicUid()).isEqualTo(authorUid);
    assertThat(item.authorHandle()).isEqualTo("Modrý čáp #4271");
    assertThat(item.product()).isSameAs(product);
  }

  @Test
  void flaggedRecordsSkipsGroupWhenTargetWasDeletedMeanwhile() {
    RecordFlagRepository.FlaggedGroup group = flaggedGroup("PRODUCT", PRODUCT_ID, 3L, null);
    when(recordFlagRepository.findUnresolvedGroups(null, 20, 0)).thenReturn(List.of(group));
    when(recordFlagRepository.countUnresolvedGroups(null)).thenReturn(1L);
    when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of());
    when(storeRepository.findAllById(List.of())).thenReturn(List.of());
    when(mediaRepository.findAllById(List.of())).thenReturn(List.of());

    FlaggedRecordResult result = service.flaggedRecords(null, null, null, MODERATOR_ID);

    assertThat(result.items()).isEmpty();
  }

  /**
   * Nahlášená recenze v moderátorské frontě nese product (kontext textu), autor je autor
   * RECENZE (core.product_review.user_id), ne autor zboží — na rozdíl od PRODUCT/STORE výš.
   */
  @Test
  void flaggedRecordsMapsReviewGroupWithProductContext() {
    Long reviewId = 5L;
    RecordFlagRepository.FlaggedGroup group = flaggedGroup("REVIEW", reviewId, 2L, "urážlivý text");
    when(recordFlagRepository.findUnresolvedGroups(null, 20, 0)).thenReturn(List.of(group));
    when(recordFlagRepository.countUnresolvedGroups(null)).thenReturn(1L);

    Long reviewAuthorId = 77L;
    ProductReview review = ProductReview.builder().id(reviewId).productId(PRODUCT_ID).userId(reviewAuthorId)
        .stars((short) 1).text("hrozné").build();
    when(productReviewRepository.findAllById(List.of(reviewId))).thenReturn(List.of(review));

    Product product = Product.builder().id(PRODUCT_ID).build();
    when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(product));
    when(storeRepository.findAllById(List.of())).thenReturn(List.of());
    when(mediaRepository.findAllById(List.of())).thenReturn(List.of());

    UUID authorUid = UUID.randomUUID();
    AppUser author = AppUser.builder().id(reviewAuthorId).publicUid(authorUid).build();
    when(appUserRepository.findAllById(any())).thenReturn(List.of(author));
    when(handleRenderer.render(author)).thenReturn("Modrý čáp #4271");

    FlaggedRecordResult result = service.flaggedRecords(null, null, null, MODERATOR_ID);

    assertThat(result.items()).hasSize(1);
    FlaggedRecordItem item = result.items().get(0);
    assertThat(item.recordType()).isEqualTo(RecordType.REVIEW);
    assertThat(item.authorPublicUid()).isEqualTo(authorUid);
    assertThat(item.product()).isNull();
    assertThat(item.review()).isNotNull();
    assertThat(item.review().text()).isEqualTo("hrozné");
    assertThat(item.review().product()).isSameAs(product);
  }

  // --- resolveFlags ---

  @Test
  void resolveFlagsDismissedClearsHiddenAt() {
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    Product hidden = Product.builder().id(PRODUCT_ID).hiddenAt(OffsetDateTime.now()).build();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(hidden));

    service.resolveFlags(RecordType.PRODUCT, PRODUCT_ID, FlagResolution.DISMISSED, MODERATOR_ID);

    verify(recordFlagRepository).resolveAllPending("PRODUCT", PRODUCT_ID, MODERATOR_ID, "DISMISSED");
    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    assertThat(captor.getValue().getHiddenAt()).isNull();
  }

  @Test
  void resolveFlagsUpheldHidesRecordEvenBelowThreshold() {
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
    Product notYetHidden = Product.builder().id(PRODUCT_ID).hiddenAt(null).build();
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(notYetHidden));

    service.resolveFlags(RecordType.PRODUCT, PRODUCT_ID, FlagResolution.UPHELD, MODERATOR_ID);

    ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    assertThat(captor.getValue().getHiddenAt()).isNotNull();
  }

  @Test
  void resolveFlagsThrowsWhenRecordDoesNotExist() {
    when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.resolveFlags(RecordType.PRODUCT, PRODUCT_ID, FlagResolution.DISMISSED, MODERATOR_ID))
        .isInstanceOf(NotFoundException.class);
    verify(recordFlagRepository, never()).resolveAllPending(any(), any(), any(), any());
  }

  // --- setObservationRejected ---

  @Test
  void setObservationRejectedEnqueuesRecompute() {
    Long observationId = 55L;
    Product product = Product.builder().id(PRODUCT_ID).build();
    Store store = Store.builder().id(3L).build();
    PriceObservation observation = PriceObservation.builder().id(observationId).product(product).store(store).build();
    when(priceObservationRepository.findById(observationId)).thenReturn(Optional.of(observation));
    when(priceObservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    PriceObservation result = service.setObservationRejected(observationId, true, "nesmyslná cena");

    assertThat(result.getStatus()).isEqualTo(cz.kvalitacena.db.entity.ObservationStatus.REJECTED);
    verify(priceAggregationService).enqueueRecompute(PRODUCT_ID, 3L, RecomputeReason.MODERATION);
  }

  // --- moderationObservations ---

  /**
   * "Kolik ze tří" pro DRAFT zboží (docs/reputace.md) se počítá přes {@code ProductCatalogService}
   * — tenhle test hlídá jen zapojení (findAllById produktů, předání dávkového počtu do
   * productStatus), samotný výpočet má vlastní testy v ProductCatalogServiceTest.
   */
  @Test
  void moderationObservationsIncludesProductPublicationStatus() {
    Product draftProduct = Product.builder().id(PRODUCT_ID).status(ProductStatus.DRAFT).build();
    Store store = Store.builder().id(3L).build();
    PriceObservation observation = PriceObservation.builder().id(55L).product(draftProduct).store(store)
        .priceKind(PriceKind.REGULAR).priceAmount(new BigDecimal("29.90")).currency("CZK")
        .observedAt(OffsetDateTime.now()).build();
    when(priceObservationRepository.findForModeration(null, null, null, 20, 0)).thenReturn(List.of(observation));
    when(priceObservationRepository.countForModeration(null, null, null)).thenReturn(1L);
    when(productRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(draftProduct));
    when(appUserRepository.findAllById(any())).thenReturn(List.of());
    when(productCatalogService.confirmationsForProducts(List.of(draftProduct)))
        .thenReturn(Map.of(PRODUCT_ID, 1L));
    PublicationStatus awaiting = PublicationStatus.awaitingConfirmations(1, 3);
    when(productCatalogService.productStatus(draftProduct, 1L)).thenReturn(awaiting);

    ModerationObservationResult result = service.moderationObservations(null, null, null, null, null);

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).productPublication()).isEqualTo(awaiting);
    assertThat(result.items().get(0).productPublication().state()).isEqualTo(PublicationState.AWAITING_CONFIRMATIONS);
  }

  // --- setUserSuspended ---

  @Test
  void setUserSuspendedRevokesTokensAndBumpsVersion() {
    UUID publicUid = UUID.randomUUID();
    AppUser user = AppUser.builder().id(9L).publicUid(publicUid).status(AppUserStatus.ACTIVE).tokenVersion(0).build();
    when(appUserRepository.findByPublicUid(publicUid)).thenReturn(Optional.of(user));
    when(appUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.setUserSuspended(publicUid, true, "spam");

    ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
    verify(appUserRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(AppUserStatus.SUSPENDED);
    assertThat(captor.getValue().getTokenVersion()).isEqualTo(1);
    verify(refreshTokenService).revokeAllForUser(9L, RevokeReason.SUSPENDED);
  }

  @Test
  void setUserSuspendedFalseRestoresActiveWithoutRevoking() {
    UUID publicUid = UUID.randomUUID();
    AppUser user = AppUser.builder().id(9L).publicUid(publicUid).status(AppUserStatus.SUSPENDED).tokenVersion(2).build();
    when(appUserRepository.findByPublicUid(publicUid)).thenReturn(Optional.of(user));
    when(appUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.setUserSuspended(publicUid, false, null);

    ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
    verify(appUserRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(AppUserStatus.ACTIVE);
    assertThat(captor.getValue().getTokenVersion()).isEqualTo(2);
    verify(refreshTokenService, never()).revokeAllForUser(any(), any());
  }

  private RecordFlagRepository.FlaggedGroup flaggedGroup(String recordType, Long recordId, long flagCount, String reasons) {
    return new RecordFlagRepository.FlaggedGroup() {
      @Override
      public String getRecordType() {
        return recordType;
      }

      @Override
      public Long getRecordId() {
        return recordId;
      }

      @Override
      public long getFlagCount() {
        return flagCount;
      }

      @Override
      public java.time.Instant getFirstFlaggedAt() {
        return java.time.Instant.now().minusSeconds(86400);
      }

      @Override
      public java.time.Instant getLastFlaggedAt() {
        return java.time.Instant.now();
      }

      @Override
      public String getReasons() {
        return reasons;
      }
    };
  }
}
