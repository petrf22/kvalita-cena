package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCodeRepository extends JpaRepository<ProductCode, Long> {

  List<ProductCode> findByProductId(Long productId);

  /** Vnitroobchodní kódy (STORE_INTERNAL) mají stejný `code` napříč různými chain_id, proto
   *  se pro skenování bere jen GTIN — viz docs/datovy-model.md. */
  Optional<ProductCode> findFirstByCodeAndCodeType(String code, cz.kvalitacena.db.entity.CodeType codeType);
}
