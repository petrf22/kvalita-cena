package cz.kvalitacena.service;

import cz.kvalitacena.config.ReviewProperties;
import cz.kvalitacena.controller.MyProductReview;
import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.controller.ProductReviewResult;
import cz.kvalitacena.controller.ReviewItem;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ProductReview;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import cz.kvalitacena.security.ViewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hodnocení kvality zboží — hvězdičky 1–5 (5 nejlepší) povinně, text recenze (max
 * {@link ReviewProperties#getMaxTextLength()} znaků) volitelně. Tabulka se dřív jmenovala
 * {@code core.product_quality_rating} a tahle třída {@code QualityRatingService} — přejmenováno
 * na {@code core.product_review}/{@code ProductReviewService}
 * (2026-09-01/02-rename-product-review.yaml) jako první krok před přidáním textu recenze.
 *
 * <p>Text recenze vidí jen přihlášený (docs/reputace.md, T1) — {@link #reviewsFor} to řeší
 * ořezáním v service, ne filtrem v resolveru (stejný vzor jako {@code PriceHistoryService}
 * u anonymního okna grafu). Podepsaná recenze je první veřejné místo, kde autor vyleze z API
 * (docs/soukromi.md, "Podepsaná recenze") — jméno vykresluje {@link PublicNameRenderer}.
 */
@Service
@RequiredArgsConstructor
public class ProductReviewService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;

  private final ProductReviewRepository reviewRepository;
  private final AppUserRepository appUserRepository;
  private final ProductRepository productRepository;
  private final PublicNameRenderer publicNameRenderer;
  private final ReviewProperties reviewProperties;
  private final CatalogRateLimiter catalogRateLimiter;

  @Transactional
  public ProductQuality rate(Long productId, int stars, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.QUALITY_REQUIRES_LOGIN);
    }
    if (stars < 1 || stars > 5) {
      throw new ValidationException(ErrorCode.QUALITY_STARS_OUT_OF_RANGE, 1, 5);
    }
    if (!productRepository.existsById(productId)) {
      throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));

    // Upsert nesahá na text/text_updated_at — opakované hodnocení hvězdičkami existující text
    // nemaže (docs/rozvoj.md nápad "hodnotit textově" to výslovně požaduje).
    reviewRepository.upsert(productId, user.getId(), (short) stars);

    return summariesFor(List.of(productId)).getOrDefault(productId, ProductQuality.EMPTY);
  }

  @Transactional(readOnly = true)
  public Map<Long, ProductQuality> summariesFor(Collection<Long> productIds) {
    Map<Long, ProductQuality> result = new HashMap<>();
    if (productIds.isEmpty()) return result;
    for (ProductReviewRepository.QualityRow row : reviewRepository.summarize(productIds)) {
      BigDecimal average = row.getAverage() == null
          ? null
          : BigDecimal.valueOf(row.getAverage()).setScale(2, RoundingMode.HALF_UP);
      result.put(row.getProductId(), new ProductQuality(average, (int) row.getCount()));
    }
    return result;
  }

  /** Pro Product.myQualityRating — anonym nemá nic hodnoceno, vrací prázdnou mapu. */
  @Transactional(readOnly = true)
  public Map<Long, Integer> starsOf(UUID viewerPublicUid, Collection<Long> productIds) {
    Map<Long, Integer> result = new HashMap<>();
    if (viewerPublicUid == null || productIds.isEmpty()) return result;
    appUserRepository.findByPublicUid(viewerPublicUid).ifPresent(user -> {
      for (ProductReviewRepository.StarsRow row : reviewRepository.starsOfUser(user.getId(), productIds)) {
        result.put(row.getProductId(), (int) row.getStars());
      }
    });
    return result;
  }

  /** Pro Product.reviewCount — počet recenzí S TEXTEM, ne totéž co quality.count. */
  @Transactional(readOnly = true)
  public Map<Long, Integer> reviewCountsFor(Collection<Long> productIds) {
    Map<Long, Integer> result = new HashMap<>();
    if (productIds.isEmpty()) return result;
    for (ProductReviewRepository.ReviewCountRow row : reviewRepository.countVisibleTextsByProducts(productIds)) {
      result.put(row.getProductId(), (int) row.getCount());
    }
    return result;
  }

  /** Pro Product.myReviewText — anonym nemá nic napsáno, vrací prázdnou mapu. */
  @Transactional(readOnly = true)
  public Map<Long, String> myReviewTextsOf(UUID viewerPublicUid, Collection<Long> productIds) {
    Map<Long, String> result = new HashMap<>();
    if (viewerPublicUid == null || productIds.isEmpty()) return result;
    appUserRepository.findByPublicUid(viewerPublicUid).ifPresent(user -> {
      for (ProductReviewRepository.TextRow row : reviewRepository.textsOfUser(user.getId(), productIds)) {
        result.put(row.getProductId(), row.getText());
      }
    });
    return result;
  }

  /**
   * Recenze pod zbožím. Nepřihlášený dostane {@code loginRequired=true} a prázdné
   * {@code items}, ale SKUTEČNÝ {@code totalCount} — appka tím umí napsat "N recenzí, přihlas
   * se pro zobrazení" místo tichého prázdna (docs/reputace.md, T1).
   */
  @Transactional(readOnly = true)
  public ProductReviewResult reviewsFor(Long productId, Integer first, Integer offset, ViewerContext viewer) {
    int limit = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int off = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    long total = reviewRepository.countVisibleTextsByProduct(productId);

    if (viewer.userId() == null) {
      return new ProductReviewResult(List.of(), (int) total, false, true);
    }

    List<ProductReview> page = reviewRepository.findVisibleTextsByProduct(productId, limit, off);
    Map<Long, AppUser> authorsById = appUserRepository.findAllById(
            page.stream().map(ProductReview::getUserId).distinct().toList()).stream()
        .collect(Collectors.toMap(AppUser::getId, Function.identity()));

    List<ReviewItem> items = page.stream()
        .map(r -> toItem(r, authorsById.get(r.getUserId()), viewer))
        .toList();
    return new ProductReviewResult(items, (int) total, off + items.size() < total, false);
  }

  private ReviewItem toItem(ProductReview review, AppUser author, ViewerContext viewer) {
    // author je vždy dohledaný — fk_product_review_user garantuje existenci (ON DELETE
    // CASCADE smaže i recenzi, takže osiřelý řádek nemůže vzniknout).
    return new ReviewItem(review.getId(), review.getStars(), review.getText(), author.getPublicUid(),
        publicNameRenderer.render(author, viewer), review.getCreatedAt(), review.getTextUpdatedAt(),
        review.getUserId().equals(viewer.userId()));
  }

  /**
   * Zapsání/úprava textu k VLASTNÍMU hodnocení — hvězdičky musí existovat dřív
   * (REVIEW_REQUIRES_RATING), text sám bez hvězdiček nejde založit. Text recenze je první
   * veřejný text v appce, proto vlastní denní strop (CatalogRateLimiter, oddělený od
   * rateProduct — samotné hvězdičky rate limitu nepodléhají).
   */
  @Transactional
  public MyProductReview saveText(Long productId, String text, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.REVIEW_REQUIRES_LOGIN);
    }
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.isEmpty()) {
      throw new ValidationException(ErrorCode.REVIEW_TEXT_EMPTY);
    }
    if (trimmed.length() > reviewProperties.getMaxTextLength()) {
      throw new ValidationException(ErrorCode.REVIEW_TEXT_TOO_LONG, reviewProperties.getMaxTextLength());
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    ProductReview review = reviewRepository.findByProductIdAndUserId(productId, user.getId())
        .orElseThrow(() -> new ValidationException(ErrorCode.REVIEW_REQUIRES_RATING));
    if (!catalogRateLimiter.tryAcquireReviewText(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    review.setText(trimmed);
    review.setTextUpdatedAt(OffsetDateTime.now());
    reviewRepository.save(review);
    return new MyProductReview(review.getStars(), review.getText(), review.getTextUpdatedAt());
  }

  /** Smazání textu — hvězdičky zůstávají beze změny. Idempotentní, žádný rate limit. */
  @Transactional
  public MyProductReview deleteText(Long productId, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.REVIEW_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
    ProductReview review = reviewRepository.findByProductIdAndUserId(productId, user.getId())
        .orElseThrow(() -> new ValidationException(ErrorCode.REVIEW_REQUIRES_RATING));

    review.setText(null);
    review.setTextUpdatedAt(null);
    reviewRepository.save(review);
    return new MyProductReview(review.getStars(), null, null);
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
