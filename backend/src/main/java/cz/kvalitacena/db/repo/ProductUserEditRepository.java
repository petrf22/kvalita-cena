package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductUserEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductUserEditRepository extends JpaRepository<ProductUserEdit, ProductUserEdit.Id> {

  Optional<ProductUserEdit> findByProductIdAndUserId(Long productId, Long userId);

  /** Dávkové dotažení patchů pro seznam produktů a JEDNOHO viewera (hledání, ne N+1). */
  List<ProductUserEdit> findByProductIdInAndUserId(Collection<Long> productIds, Long userId);
}
