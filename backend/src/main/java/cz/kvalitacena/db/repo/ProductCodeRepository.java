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

  /**
   * EAN z potvrzeného řádku účtenky ke zboží, které žádný GTIN nemá (ReceiptReviewService).
   * Jeden příkaz bez výjimek: ON CONFLICT DO NOTHING pokryje EAN, který už patří jinému
   * zboží (uq_product_code_code_type_chain), a NOT EXISTS zboží, které mezitím kód dostalo.
   * Výjimka by tu shodila savepoint potvrzení řádku, takže by se nezapsala ani cena.
   *
   * @return 1, když se kód zapsal, jinak 0
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(value = "INSERT INTO core.product_code (product_id, code, code_type, is_primary) "
      + "SELECT :productId, :gtin14, 'GTIN', true "
      + "WHERE NOT EXISTS (SELECT 1 FROM core.product_code WHERE product_id = :productId AND code_type = 'GTIN') "
      + "ON CONFLICT DO NOTHING", nativeQuery = true)
  int insertGtinIfProductHasNone(@org.springframework.data.repository.query.Param("productId") Long productId,
      @org.springframework.data.repository.query.Param("gtin14") String gtin14);
}
