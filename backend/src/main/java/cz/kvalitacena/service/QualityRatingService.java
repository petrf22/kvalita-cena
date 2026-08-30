package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductQualityRatingRepository;
import cz.kvalitacena.db.repo.ProductRepository;
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
 * Hodnocení kvality zboží — jen hvězdičky 1–5 (5 nejlepší), bez textů. Etapa 2
 * (core.product_review, viditelnost, ViewerContext) tady vědomě neexistuje, viz
 * docs/datovy-model.md a CLAUDE.md.
 */
@Service
@RequiredArgsConstructor
public class QualityRatingService {

  private final ProductQualityRatingRepository qualityRatingRepository;
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

    qualityRatingRepository.upsert(productId, user.getId(), (short) stars);

    return summariesFor(java.util.List.of(productId)).getOrDefault(productId, ProductQuality.EMPTY);
  }

  @Transactional(readOnly = true)
  public Map<Long, ProductQuality> summariesFor(Collection<Long> productIds) {
    Map<Long, ProductQuality> result = new HashMap<>();
    if (productIds.isEmpty()) return result;
    for (ProductQualityRatingRepository.QualityRow row : qualityRatingRepository.summarize(productIds)) {
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
      for (ProductQualityRatingRepository.StarsRow row : qualityRatingRepository.starsOfUser(user.getId(), productIds)) {
        result.put(row.getProductId(), (int) row.getStars());
      }
    });
    return result;
  }
}
