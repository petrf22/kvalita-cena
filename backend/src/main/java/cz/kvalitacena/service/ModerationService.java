package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.DuplicateCandidate;
import cz.kvalitacena.controller.DuplicateCandidateResult;
import cz.kvalitacena.controller.FlaggedRecordItem;
import cz.kvalitacena.controller.FlaggedRecordResult;
import cz.kvalitacena.controller.FlaggedReview;
import cz.kvalitacena.controller.ModerationObservationItem;
import cz.kvalitacena.controller.ModerationObservationResult;
import cz.kvalitacena.controller.Photo;
import cz.kvalitacena.controller.PublicationStatus;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.FlagResolution;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.ObservationStatus;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.entity.RecomputeReason;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.RevokeReason;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.DuplicateCandidateRow;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.db.repo.RecordFlagRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.security.RefreshTokenService;
import cz.kvalitacena.security.ViewerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nástroj pro T4 (docs/reputace.md, "Odstupňování přístupu"; "Moderace") — přezkum fronty
 * nahlášených záznamů (core.record_flag), zamítání jednotlivých cen a pozastavování účtů
 * (docs/podminky-uziti.md, "Moderace a nahlašování"; "Ukončení a vyloučení"). Autorizace
 * (jen moderátor) je predikát v {@code ModerationGraphQlController}, ne tady — stejná
 * konvence jako zbytek appky (CLAUDE.md, "autorizace jako predikát").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;
  // Unit Separator (chr(31) v RecordFlagRepository.findUnresolvedGroups) — nikdy se
  // nevyskytne v lidsky psaném textu, na rozdíl od čárky/středníku.
  private static final String REASON_SEPARATOR = "\u001F";

  private final RecordFlagRepository recordFlagRepository;
  private final ProductRepository productRepository;
  private final CatalogProperties catalogProperties;
  private final StoreRepository storeRepository;
  private final MediaRepository mediaRepository;
  private final ProductReviewRepository productReviewRepository;
  private final AppUserRepository appUserRepository;
  private final ProductOverlayService productOverlayService;
  private final StoreOverlayService storeOverlayService;
  private final MediaService mediaService;
  private final HandleRenderer handleRenderer;
  private final PriceObservationRepository priceObservationRepository;
  private final PriceAggregationService priceAggregationService;
  private final RefreshTokenService refreshTokenService;
  private final ProductCatalogService productCatalogService;

  @Transactional(readOnly = true)
  /**
   * Fronta podezřelých duplicit bezkódového zboží. Na rozdíl od {@link #flaggedRecords} nestojí
   * na nahlášení — duplicitu nikdo nenahlásí, protože nikoho nepoškozuje, jen tiše rozděluje
   * ceny jedné věci do dvou košů a tím kazí agregát (docs/reputace.md, "Zboží bez čárového
   * kódu"). Sloučení zůstává ruční, tohle je jen seřazená nabídka k přezkumu.
   */
  public DuplicateCandidateResult duplicateCandidates(Integer first, Integer offset, Long moderatorUserId) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    double threshold = catalogProperties.getDuplicateSimilarity();

    List<DuplicateCandidateRow> rows = productRepository.findDuplicateCandidates(threshold, limit, off);
    long total = productRepository.countDuplicateCandidates(threshold);

    Set<Long> productIds = new LinkedHashSet<>();
    rows.forEach(row -> {
      productIds.add(row.getLeftId());
      productIds.add(row.getRightId());
    });
    Map<Long, Product> productsById = index(
        productOverlayService.applyOverlay(productRepository.findAllById(productIds), moderatorUserId),
        Product::getId);

    List<DuplicateCandidate> items = rows.stream()
        .map(row -> new DuplicateCandidate(
            productsById.get(row.getLeftId()), productsById.get(row.getRightId()), row.getScore()))
        .filter(candidate -> candidate.left() != null && candidate.right() != null)
        .toList();
    return new DuplicateCandidateResult(items, (int) total, off + rows.size() < total);
  }

  public FlaggedRecordResult flaggedRecords(RecordType recordTypeFilter, Integer first, Integer offset,
      Long moderatorUserId) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    String filter = recordTypeFilter == null ? null : recordTypeFilter.name();

    List<RecordFlagRepository.FlaggedGroup> groups = recordFlagRepository.findUnresolvedGroups(filter, limit, off);
    long total = recordFlagRepository.countUnresolvedGroups(filter);

    Map<Long, ProductReview> reviewsById = index(
        productReviewRepository.findAllById(idsOf(groups, RecordType.REVIEW)), ProductReview::getId);

    // Produkty nejen za PRODUCT-typem nahlášení, ale i produkty patřící nahlášeným recenzím
    // (FlaggedReview.product) — jeden dotaz místo dvou.
    Set<Long> productIdSet = new LinkedHashSet<>(idsOf(groups, RecordType.PRODUCT));
    reviewsById.values().forEach(r -> productIdSet.add(r.getProductId()));
    List<Long> productIds = List.copyOf(productIdSet);
    Map<Long, Product> productsById = index(
        productOverlayService.applyOverlay(productRepository.findAllById(productIds), moderatorUserId),
        Product::getId);
    Map<Long, Store> storesById = index(
        storeOverlayService.applyOverlay(storeRepository.findAllById(idsOf(groups, RecordType.STORE)), moderatorUserId),
        Store::getId);
    Map<Long, Media> mediaById = index(mediaRepository.findAllById(idsOf(groups, RecordType.PHOTO)), Media::getId);

    Set<Long> authorIds = new LinkedHashSet<>();
    productsById.values().forEach(p -> addIfPresent(authorIds, p.getCreatedByUserId()));
    storesById.values().forEach(s -> addIfPresent(authorIds, s.getCreatedByUserId()));
    mediaById.values().forEach(m -> addIfPresent(authorIds, m.getUploadedByUserId()));
    reviewsById.values().forEach(r -> addIfPresent(authorIds, r.getUserId()));
    Map<Long, AppUser> authorsById = index(appUserRepository.findAllById(authorIds), AppUser::getId);

    // publicUid/trusted nejsou pro sestavení fronty potřeba, jen userId (autorizace předchozím
    // krokem v kontroleru) a moderator=true kvůli MediaService.toPhoto (výpočet pole "mine").
    ViewerContext moderatorViewer = new ViewerContext(null, moderatorUserId, false, true);

    List<FlaggedRecordItem> items = groups.stream()
        .map(g -> toItem(g, productsById, storesById, mediaById, reviewsById, authorsById, moderatorViewer))
        .filter(Objects::nonNull)
        .toList();

    return new FlaggedRecordResult(items, (int) total, off + items.size() < total);
  }

  /**
   * DISMISSED vrátí {@code hidden_at} na NULL — jediná cesta zpět, jinak by chybné nahlášení
   * bylo trvalé (RecordFlagService.hideRecord byl dřív jednosměrný). UPHELD skrytí ponechá,
   * nebo ho nastaví, i když práh nahlášení ještě nebyl dosažen (moderátorovo rozhodnutí je
   * silnější než automatický práh). Druhé volání nad stejným záznamem je no-op
   * (resolveAllPending aktualizuje 0 řádků, hidden_at se jen znovu nastaví na stejnou hodnotu).
   */
  @Transactional
  public void resolveFlags(RecordType recordType, Long recordId, FlagResolution resolution, Long moderatorUserId) {
    boolean exists = switch (recordType) {
      case PRODUCT -> productRepository.existsById(recordId);
      case STORE -> storeRepository.existsById(recordId);
      case PHOTO -> mediaRepository.existsById(recordId);
      case REVIEW -> productReviewRepository.existsById(recordId);
      case USER -> false; // core.record_flag USER nikdy nepoužívá, viz RecordFlagService
    };
    if (!exists) {
      throw new NotFoundException(ErrorCode.MODERATION_RECORD_NOT_FOUND);
    }

    recordFlagRepository.resolveAllPending(recordType.name(), recordId, moderatorUserId, resolution.name());

    switch (recordType) {
      case PRODUCT -> productRepository.findById(recordId).ifPresent(p -> {
        applyHiddenAt(resolution, p.getHiddenAt(), p::setHiddenAt);
        productRepository.save(p);
      });
      case STORE -> storeRepository.findById(recordId).ifPresent(s -> {
        applyHiddenAt(resolution, s.getHiddenAt(), s::setHiddenAt);
        storeRepository.save(s);
      });
      case PHOTO -> mediaRepository.findById(recordId).ifPresent(m -> {
        applyHiddenAt(resolution, m.getHiddenAt(), m::setHiddenAt);
        mediaRepository.save(m);
      });
      case REVIEW -> productReviewRepository.findById(recordId).ifPresent(r -> {
        applyHiddenAt(resolution, r.getHiddenAt(), r::setHiddenAt);
        productReviewRepository.save(r);
      });
      case USER -> {
      }
    }
  }

  private void applyHiddenAt(FlagResolution resolution, OffsetDateTime currentHiddenAt,
      Consumer<OffsetDateTime> setter) {
    if (resolution == FlagResolution.DISMISSED) {
      setter.accept(null);
    } else if (currentHiddenAt == null) {
      setter.accept(OffsetDateTime.now());
    }
  }

  @Transactional(readOnly = true)
  public ModerationObservationResult moderationObservations(UUID authorPublicUid, Long productId, Long storeId,
      Integer first, Integer offset) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);

    List<PriceObservation> page =
        priceObservationRepository.findForModeration(authorPublicUid, productId, storeId, limit, off);
    long total = priceObservationRepository.countForModeration(authorPublicUid, productId, storeId);

    // submitter/product jsou LAZY proxy z nativního dotazu — getId() je nenačte, dávkové
    // findAllById zůstává jediným dotazem navíc na autory i na produkty (žádný N+1), stejný
    // vzor jako MyContributionsService.myObservations.
    Set<Long> submitterIds = new LinkedHashSet<>();
    page.forEach(o -> {
      if (o.getSubmitter() != null) addIfPresent(submitterIds, o.getSubmitter().getId());
    });
    List<Long> productIds = page.stream().map(o -> o.getProduct().getId()).distinct().toList();
    Map<Long, AppUser> authorsById = index(appUserRepository.findAllById(submitterIds), AppUser::getId);
    Map<Long, Product> productsById = index(productRepository.findAllById(productIds), Product::getId);
    Map<Long, Long> confirmations = productCatalogService.confirmationsForProducts(List.copyOf(productsById.values()));

    List<ModerationObservationItem> items = page.stream()
        .map(o -> {
          AppUser author = o.getSubmitter() == null ? null : authorsById.get(o.getSubmitter().getId());
          Product product = productsById.get(o.getProduct().getId());
          PublicationStatus productPublication =
              productCatalogService.productStatus(product, confirmations.getOrDefault(product.getId(), 0L));
          return new ModerationObservationItem(o, author == null ? null : author.getPublicUid(),
              author == null ? null : handleRenderer.render(author), productPublication);
        })
        .toList();

    return new ModerationObservationResult(items, (int) total, off + items.size() < total);
  }

  @Transactional
  public PriceObservation setObservationRejected(Long id, boolean rejected, String reason) {
    PriceObservation observation = priceObservationRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_OBSERVATION_NOT_FOUND));
    observation.setStatus(rejected ? ObservationStatus.REJECTED : ObservationStatus.ACTIVE);
    observation = priceObservationRepository.save(observation);
    // Bez tohohle by REJECTED/obnovená cena zůstala v agg.price_current/price_daily beze
    // změny ještě několik dní — bulk operace nad price_observation sám žádný přepočet
    // nespustí, viz PriceAggregationService.enqueueRecompute.
    priceAggregationService.enqueueRecompute(observation.getProduct().getId(), observation.getStore().getId(),
        RecomputeReason.MODERATION);
    log.info("Moderátor {} cenu {} (produkt {}, obchod {}), důvod: {}",
        rejected ? "zamítl" : "obnovil", id, observation.getProduct().getId(), observation.getStore().getId(), reason);
    return observation;
  }

  @Transactional
  public void setUserSuspended(UUID publicUid, boolean suspended, String reason) {
    AppUser user = appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_USER_NOT_FOUND));
    user.setStatus(suspended ? AppUserStatus.SUSPENDED : AppUserStatus.ACTIVE);
    if (suspended) {
      // token_version navíc k revokaci refresh tokenů — stejná dvojice jako u ostatních
      // globálních odhlášení v appce (EmailChangeService), i když JwtAuthenticationFilter a
      // RefreshTokenService.rotate už samy o sobě kontrolují status != ACTIVE.
      user.setTokenVersion(user.getTokenVersion() + 1);
    }
    appUserRepository.save(user);
    if (suspended) {
      refreshTokenService.revokeAllForUser(user.getId(), RevokeReason.SUSPENDED);
    }
    log.info("Moderátor {} účet {}, důvod: {}", suspended ? "pozastavil" : "obnovil",
        user.getPublicHandle(), reason);
  }

  private FlaggedRecordItem toItem(RecordFlagRepository.FlaggedGroup g, Map<Long, Product> productsById,
      Map<Long, Store> storesById, Map<Long, Media> mediaById, Map<Long, ProductReview> reviewsById,
      Map<Long, AppUser> authorsById, ViewerContext moderatorViewer) {
    RecordType type = RecordType.valueOf(g.getRecordType());
    Long authorId;
    boolean hidden;
    Product product = null;
    Store store = null;
    Photo photo = null;
    FlaggedReview review = null;
    switch (type) {
      case PRODUCT -> {
        product = productsById.get(g.getRecordId());
        if (product == null) return null; // mezitím smazáno/sloučeno
        authorId = product.getCreatedByUserId();
        hidden = product.getHiddenAt() != null;
      }
      case STORE -> {
        store = storesById.get(g.getRecordId());
        if (store == null) return null;
        authorId = store.getCreatedByUserId();
        hidden = store.getHiddenAt() != null;
      }
      case PHOTO -> {
        Media media = mediaById.get(g.getRecordId());
        if (media == null) return null;
        authorId = media.getUploadedByUserId();
        hidden = media.isHidden();
        photo = mediaService.toPhoto(media, moderatorViewer);
      }
      case REVIEW -> {
        ProductReview productReview = reviewsById.get(g.getRecordId());
        if (productReview == null) return null; // mezitím smazáno (výmaz účtu, smazání textu)
        Product reviewProduct = productsById.get(productReview.getProductId());
        if (reviewProduct == null) return null; // produkt mezitím smazán (FK by smazal i recenzi, ale pro jistotu)
        authorId = productReview.getUserId();
        hidden = productReview.isHidden();
        review = new FlaggedReview(productReview.getId(), productReview.getStars(), productReview.getText(), reviewProduct);
      }
      default -> {
        return null; // core.record_flag USER nikdy nepoužívá
      }
    }
    AppUser author = authorId == null ? null : authorsById.get(authorId);
    return new FlaggedRecordItem(type, g.getRecordId(), g.getFlagCount(),
        g.getFirstFlaggedAt().atOffset(ZoneOffset.UTC), g.getLastFlaggedAt().atOffset(ZoneOffset.UTC),
        splitReasons(g.getReasons()), hidden,
        author == null ? null : author.getPublicUid(), author == null ? null : handleRenderer.render(author),
        product, store, photo, review);
  }

  private List<Long> idsOf(List<RecordFlagRepository.FlaggedGroup> groups, RecordType type) {
    return groups.stream()
        .filter(g -> g.getRecordType().equals(type.name()))
        .map(RecordFlagRepository.FlaggedGroup::getRecordId)
        .distinct()
        .toList();
  }

  private List<String> splitReasons(String joined) {
    if (joined == null || joined.isBlank()) return List.of();
    return List.of(joined.split(REASON_SEPARATOR));
  }

  private void addIfPresent(Set<Long> set, Long id) {
    if (id != null) set.add(id);
  }

  private <T> Map<Long, T> index(List<T> entities, Function<T, Long> idFn) {
    return entities.stream().collect(Collectors.toMap(idFn, Function.identity()));
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
