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
import java.util.Optional;

public interface PriceDailyRepository extends JpaRepository<PriceDaily, PriceDailyId> {

  @Modifying
  void deleteByProductIdAndStoreId(Long productId, Long storeId);

  List<PriceDaily> findByProductIdAndStoreIdAndPriceKindAndCurrencyAndDayGreaterThanEqualOrderByDayAsc(
      Long productId, Long storeId, PriceKind priceKind, String currency, LocalDate from);

  /**
   * Když volající (PriceHistoryService) nezná měnu předem — národní graf ukáže měnu, ve které
   * je za dané období nejvíc záznamů (ne první abecedně/náhodně), docs/lokalizace.md.
   */
  @Query(value = """
      SELECT d.currency AS currency
      FROM agg.price_daily d
      WHERE d.product_id = :productId AND d.price_kind = :priceKind AND d.day >= :fromDay
      GROUP BY d.currency
      ORDER BY SUM(d.n_obs) DESC
      LIMIT 1
      """, nativeQuery = true)
  Optional<String> dominantCurrency(@Param("productId") Long productId,
      @Param("priceKind") String priceKind, @Param("fromDay") LocalDate fromDay);

  /**
   * Celostátní řada = medián mediánů (docs/reputace.md): denní medián uvnitř provozovny je
   * už v tabulce, tady se přes provozovny bere medián těch mediánů. Bez toho by provozovna
   * s mnoha záznamy přebila spoustu provozoven s jedním záznamem. Filtr na currency je nutný,
   * jinak by se do jednoho dne mísily medián CZK a medián EUR provozoven (docs/lokalizace.md).
   */
  @Query(value = """
      SELECT d.day                                                       AS day,
             percentile_cont(0.5) WITHIN GROUP (ORDER BY d.unit_price)    AS unit_price,
             percentile_cont(0.5) WITHIN GROUP (ORDER BY d.price_amount)  AS price_amount,
             SUM(d.n_obs)                                                 AS n_obs,
             COUNT(*)                                                     AS store_count
      FROM agg.price_daily d
      WHERE d.product_id = :productId AND d.price_kind = :priceKind AND d.currency = :currency
        AND d.day >= :fromDay
      GROUP BY d.day ORDER BY d.day
      """, nativeQuery = true)
  List<PricePointRow> nationalHistory(@Param("productId") Long productId,
      @Param("priceKind") String priceKind, @Param("currency") String currency,
      @Param("fromDay") LocalDate fromDay);

  interface PricePointRow {
    LocalDate getDay();

    BigDecimal getUnitPrice();

    BigDecimal getPriceAmount();

    int getNObs();

    int getStoreCount();
  }
}
