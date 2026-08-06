package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductQuality;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductQualityRatingRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
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
 * Hodnocení kvality zboží — jen známka 1–5 (jako ve škole, 1 nejlepší), bez textů. Etapa 2
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
  public ProductQuality rate(Long productId, int grade, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException("Hodnocení kvality vyžaduje přihlášení");
    }
    if (grade < 1 || grade > 5) {
      throw new IllegalArgumentException("Známka musí být 1 až 5");
    }
    if (!productRepository.existsById(productId)) {
      throw new NotFoundException("Produkt s tímto id neexistuje");
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException("Účet už neexistuje"));

    qualityRatingRepository.upsert(productId, user.getId(), (short) grade);

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
  public Map<Long, Integer> gradesOf(UUID viewerPublicUid, Collection<Long> productIds) {
    Map<Long, Integer> result = new HashMap<>();
    if (viewerPublicUid == null || productIds.isEmpty()) return result;
    appUserRepository.findByPublicUid(viewerPublicUid).ifPresent(user -> {
      for (ProductQualityRatingRepository.GradeRow row : qualityRatingRepository.gradesOfUser(user.getId(), productIds)) {
        result.put(row.getProductId(), (int) row.getGrade());
      }
    });
    return result;
  }
}
