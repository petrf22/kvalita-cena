package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductNameId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductNameRepository extends JpaRepository<ProductName, ProductNameId> {

  List<ProductName> findByProductId(Long productId);

  /** Dávkově pro překryv nad seznamem zboží (ProductOverlayService) — žádné N+1. */
  List<ProductName> findByProductIdIn(Collection<Long> productIds);

  Optional<ProductName> findByProductIdAndLang(Long productId, String lang);
}
