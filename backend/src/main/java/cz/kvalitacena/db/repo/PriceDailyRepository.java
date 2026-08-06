package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.PriceDaily;
import cz.kvalitacena.db.entity.PriceDailyId;
import cz.kvalitacena.db.entity.PriceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PriceDailyRepository extends JpaRepository<PriceDaily, PriceDailyId> {

  @Modifying
  void deleteByProductIdAndStoreId(Long productId, Long storeId);

  List<PriceDaily> findByProductIdAndStoreIdAndPriceKindAndDayGreaterThanEqualOrderByDayAsc(
      Long productId, Long storeId, PriceKind priceKind, LocalDate from);

  /**
   * Celostátní řada = medián mediánů (docs/reputace.md): denní medián uvnitř provozovny je
   * už v tabulce, tady se přes provozovny bere medián těch mediánů. Bez toho by provozovna
   * s mnoha záznamy přebila spoustu provozoven s jedním záznamem.
   */
  @Query(value = """
      SELECT d.day                                                       AS day,
             percentile_cont(0.5) WITHIN GROUP (ORDER BY d.unit_price)    AS unit_price,
             percentile_cont(0.5) WITHIN GROUP (ORDER BY d.price_amount)  AS price_amount,
             SUM(d.n_obs)                                                 AS n_obs,
             COUNT(*)                                                     AS store_count
      FROM agg.price_daily d
      WHERE d.product_id = :productId AND d.price_kind = :priceKind AND d.day >= :fromDay
      GROUP BY d.day ORDER BY d.day
      """, nativeQuery = true)
  List<PricePointRow> nationalHistory(@Param("productId") Long productId,
      @Param("priceKind") String priceKind, @Param("fromDay") LocalDate fromDay);

  interface PricePointRow {
    LocalDate getDay();

    BigDecimal getUnitPrice();

    BigDecimal getPriceAmount();

    int getNObs();

    int getStoreCount();
  }
}
