package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductCodeRepository extends JpaRepository<ProductCode, Long> {

  List<ProductCode> findByProductId(Long productId);

  /** Pro dávkové resolvery (@BatchMapping Product.codes) — vyhýbá se N+1 na seznamu produktů. */
  List<ProductCode> findByProductIdIn(Collection<Long> productIds);

  /** Vnitroobchodní kódy (STORE_INTERNAL) mají stejný `code` napříč různými chain_id, proto
   *  se pro skenování bere jen GTIN — viz docs/datovy-model.md. */
  Optional<ProductCode> findFirstByCodeAndCodeType(String code, cz.kvalitacena.db.entity.CodeType codeType);

  /** Vnitroobchodní kód z řádku účtenky (krok 1 kaskády, docs/rozvoj.md) — platí jen v řetězci. */
  @org.springframework.data.jpa.repository.Query(value = "SELECT pc.product_id FROM core.product_code pc "
      + "JOIN core.product p ON p.id = pc.product_id AND p.status IN ('ACTIVE', 'DRAFT') "
      + "WHERE pc.code = :code AND pc.code_type = 'STORE_INTERNAL' AND pc.chain_id = :chainId LIMIT 1",
      nativeQuery = true)
  Optional<Long> findStoreInternalProductId(@org.springframework.data.repository.query.Param("code") String code,
      @org.springframework.data.repository.query.Param("chainId") Long chainId);
}
