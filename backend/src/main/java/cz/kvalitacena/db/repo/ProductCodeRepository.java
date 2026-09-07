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

  /**
   * Vnitroobchodní kód VŽDY se scopem řetězce — bez {@code chainId} by šlo o globální
   * identifikátor, což je u STORE_INTERNAL nejčastější tichá chyba podobných projektů
   * (docs/datovy-model.md, „Vnitroobchodní kódy vs. globální EAN"). Používá krok 1 kaskády
   * párování řádku účtenky.
   */
  Optional<ProductCode> findFirstByCodeAndCodeTypeAndChainId(String code,
      cz.kvalitacena.db.entity.CodeType codeType, Long chainId);
}
