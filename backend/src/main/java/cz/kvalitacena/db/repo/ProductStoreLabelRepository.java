package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ProductStoreLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductStoreLabelRepository extends JpaRepository<ProductStoreLabel, Long> {

  /**
   * Krok 2 kaskády párování: přesná shoda označení v rozsahu. Rozsah se ptá zvlášť na
   * provozovnu a zvlášť na řetězec, protože pořadí (nejdřív provozovna) je součást kaskády,
   * ne detail SQL — viz docs/rozvoj.md.
   */
  @Query(value = """
      SELECT * FROM core.product_store_label
      WHERE core.norm_text(label) = core.norm_text(:label)
        AND status = 'ACTIVE'
        AND (:storeId IS NOT NULL AND store_id = :storeId
             OR :storeId IS NULL AND :chainId IS NOT NULL AND chain_id = :chainId)
      """, nativeQuery = true)
  Optional<ProductStoreLabel> findActiveByLabel(@Param("label") String label,
      @Param("storeId") Long storeId, @Param("chainId") Long chainId);

  /**
   * Krok 3 kaskády: podobnost přes pg_trgm. Skóre se počítá stejně jako u
   * {@code productSuggestions} — maximum ze {@code similarity} a {@code word_similarity},
   * takže na shodu stačí jedno slovo z označení.
   */
  @Query(value = """
      SELECT l.product_id,
             GREATEST(similarity(core.norm_text(l.label), core.norm_text(:label)),
                      word_similarity(core.norm_text(:label), core.norm_text(l.label))) AS score
      FROM core.product_store_label l
      WHERE l.status = 'ACTIVE'
        AND (:storeId IS NOT NULL AND l.store_id = :storeId
             OR :chainId IS NOT NULL AND l.chain_id = :chainId)
        AND GREATEST(similarity(core.norm_text(l.label), core.norm_text(:label)),
                     word_similarity(core.norm_text(:label), core.norm_text(l.label))) >= :threshold
      ORDER BY score DESC, l.product_id
      LIMIT 1
      """, nativeQuery = true)
  List<Object[]> findSimilar(@Param("label") String label, @Param("storeId") Long storeId,
      @Param("chainId") Long chainId, @Param("threshold") double threshold);

  /**
   * Vloží mapování nebo jen posune {@code last_seen_at} u existujícího a vrátí jediný řádek
   * {@code [id, product_id]}. Návratový typ je {@code List}, protože víc sloupců vrací Spring
   * Data jako {@code List<Object[]>} i u jednořádkového výsledku. Jeden příkaz kvůli souběhu: chycení unique výjimky uvnitř JPA
   * transakce by ji označilo rollback-only, stejný důvod jako v {@code ProductAliasService}.
   *
   * <p>Vrácené {@code product_id} je nutné porovnat s tím vkládaným: unikát je na označení
   * v rozsahu, NE na dvojici (označení, zboží), takže konflikt může znamenat, že tutéž zkratku
   * už má v témže řetězci jiná položka. To není stav k přepsání, ale případ pro moderaci
   * (typicky duplicita ke sloučení) — viz {@code ProductStoreLabelService}.
   */
  @Query(value = """
      INSERT INTO core.product_store_label(product_id, label, chain_id, store_id)
      VALUES (:productId, :label, :chainId, :storeId)
      ON CONFLICT (core.norm_text(label), COALESCE(chain_id, 0), COALESCE(store_id, 0))
      DO UPDATE SET last_seen_at = CURRENT_TIMESTAMP
      RETURNING id, product_id
      """, nativeQuery = true)
  List<Object[]> upsertReturningIdAndProduct(@Param("productId") Long productId,
      @Param("label") String label, @Param("chainId") Long chainId, @Param("storeId") Long storeId);

  @Modifying(flushAutomatically = true)
  @Query(value = """
      UPDATE core.product_store_label SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
      WHERE id = :labelId AND status = 'PENDING'
        AND (SELECT count(DISTINCT user_id) FROM core.product_store_label_confirmation
             WHERE label_id = :labelId AND user_id IS NOT NULL) >= :threshold
      """, nativeQuery = true)
  int activateIfConfirmed(@Param("labelId") Long labelId, @Param("threshold") int threshold);

  List<ProductStoreLabel> findByProductId(Long productId);
}
