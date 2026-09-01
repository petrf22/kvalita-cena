package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductReviewRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hodnocení kvality zboží — hvězdičky 1–5 (5 nejlepší), zatím bez textů. Tabulka se dřív
 * jmenovala {@code core.product_quality_rating} a tahle třída {@code QualityRatingService} —
 * přejmenováno na {@code core.product_review}/{@code ProductReviewService}
 * (2026-09-01/02-rename-product-review.yaml) jako první krok před přidáním textu recenze.
 */
@Service
@RequiredArgsConstructor
public class ProductReviewService {

  private final ProductReviewRepository reviewRepository;
  private final AppUserRepository appUserRepository;
  private final ProductRepository productRepository;

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

    reviewRepository.upsert(productId, user.getId(), (short) stars);

    return summariesFor(java.util.List.of(productId)).getOrDefault(productId, ProductQuality.EMPTY);
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
}
