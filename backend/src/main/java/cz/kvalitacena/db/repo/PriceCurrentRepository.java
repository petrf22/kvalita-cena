package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.PriceCurrent;
import cz.kvalitacena.db.entity.PriceCurrentId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PriceCurrentRepository extends JpaRepository<PriceCurrent, PriceCurrentId> {

  List<PriceCurrent> findByProductId(Long productId);

  /** Pro dávkové resolvery (@BatchMapping Product.prices) — vyhýbá se N+1 na seznamu produktů. */
  List<PriceCurrent> findByProductIdIn(Collection<Long> productIds);
}
