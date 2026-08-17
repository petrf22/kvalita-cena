package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductUserEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductUserEditRepository extends JpaRepository<ProductUserEdit, ProductUserEdit.Id> {

  Optional<ProductUserEdit> findByProductIdAndUserId(Long productId, Long userId);

  /**
   * Všechny vlastní úpravy bez ohledu na produkt — GDPR export (AccountService) i "Moje
   * příspěvky" (MyContributionsService, kde se s hodnotami z StoreUserEditRepository sloučí
   * a stránkuje v paměti; objem je "vlastní data jednoho člověka", ne celý katalog).
   */
  List<ProductUserEdit> findByUserId(Long userId);

  /** Dávkové dotažení patchů pro seznam produktů a JEDNOHO viewera (hledání, ne N+1). */
  List<ProductUserEdit> findByProductIdInAndUserId(Collection<Long> productIds, Long userId);
}
