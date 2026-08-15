package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ObservationStatus;
import cz.kvalitacena.db.entity.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

  List<PriceObservation> findByProductIdAndStoreIdAndStatus(Long productId, Long storeId, ObservationStatus status);

  /**
   * Nejstarší zapsaná cena — určuje, jak hluboko do historie backfillovat kurzovní lístek
   * (ExchangeRateSyncService, docs/lokalizace.md). Prázdná tabulka → empty, volající si poradí
   * defaultem "od dneška".
   */
  @Query("SELECT MIN(o.observedAt) FROM PriceObservation o")
  Optional<OffsetDateTime> findEarliestObservedAt();

  /**
   * Leave-one-out obdoba {@link #countDistinctContributorsExcluding} pro zboží — rozhoduje o
   * promoci DRAFT → ACTIVE (docs/reputace.md, "Zboží bez čárového kódu", "Práh důvěry pro
   * zveřejnění nového záznamu"). Bez vyloučení autora by si zakladatel odemkl vlastní DRAFT
   * sám vlastními zápisy cen. Anonymní observace se nedají mezi sebou rozlišit (submitter_id
   * je NULL, docs/soukromi.md), proto se každá počítá jako samostatný přispěvatel (COALESCE na
   * vlastní id) — pesimistický odhad "aspoň tolik různých lidí", ne přesný počet.
   */
  @Query(value = "SELECT count(DISTINCT COALESCE(submitter_id::text, id::text)) "
      + "FROM core.price_observation WHERE product_id = :productId "
      + "AND submitter_id IS DISTINCT FROM :excludingUserId", nativeQuery = true)
  long countDistinctProductContributorsExcluding(@Param("productId") Long productId,
      @Param("excludingUserId") Long excludingUserId);

  /**
   * Obdoba {@link #countDistinctProductContributorsExcluding}, ale pro provozovny (leave-one-
   * out, docs/reputace.md) — jinak by si zakladatel odemkl PENDING obchod sám třemi vlastními
   * zápisy. Anonymní observace (submitter_id NULL) se počítají dál, jen autorovy vlastní ne —
   * {@code IS DISTINCT FROM} je s NULL bezpečné oproti {@code !=}.
   */
  @Query(value = "SELECT count(DISTINCT COALESCE(submitter_id::text, id::text)) "
      + "FROM core.price_observation WHERE store_id = :storeId "
      + "AND submitter_id IS DISTINCT FROM :excludingUserId", nativeQuery = true)
  long countDistinctContributorsExcluding(@Param("storeId") Long storeId,
      @Param("excludingUserId") Long excludingUserId);

  /**
   * Poslední vlastní zápis na (produkt, obchod, druh ceny) — pro "Vaše cena" (MyPriceService).
   * Čte se přímo ze surových observací, ne z {@code agg.price_current} (ta drží komunitní
   * agregát, ne "co jsem zapsal já"), a ukáže se i dřív, než ji zpracuje agregace.
   */
  @Query(value = "SELECT DISTINCT ON (product_id, store_id, price_kind) * "
      + "FROM core.price_observation "
      + "WHERE submitter_id = :userId AND product_id IN (:productIds) AND status = 'ACTIVE' "
      + "ORDER BY product_id, store_id, price_kind, observed_at DESC", nativeQuery = true)
  List<PriceObservation> findLatestOwnByProductIdIn(@Param("userId") Long userId,
      @Param("productIds") Collection<Long> productIds);

  /**
   * Pseudonymizace (docs/soukromi.md, "Retence vazby observace → uživatel: 180 dní") —
   * {@code submitter_cohort}/{@code frozen_weight} zůstávají (reputace je průběžný čítač, ne
   * odvozená z historie jednotlivých observací), mizí jen vazba na konkrétní účet.
   */
  @Modifying
  @Query("UPDATE PriceObservation o SET o.submitter = NULL "
      + "WHERE o.observedAt < :cutoff AND o.submitter IS NOT NULL")
  int pseudonymizeObservationsBefore(@Param("cutoff") OffsetDateTime cutoff);

  /** GDPR export (AccountService) — vlastní observace včetně čitelného názvu zboží/obchodu. */
  @Query("SELECT o FROM PriceObservation o JOIN FETCH o.product JOIN FETCH o.store "
      + "WHERE o.submitter.id = :userId ORDER BY o.observedAt DESC")
  List<PriceObservation> findBySubmitterIdWithProductAndStore(@Param("userId") Long userId);

  /**
   * Výmaz účtu, režim {@code DELETE_CONTENT} (docs/soukromi.md, "GDPR") — na rozdíl od
   * {@link #pseudonymizeObservationsBefore} observace skutečně mizí, ne jen vazba na účet.
   * Dotčené buňky agregátu je potřeba zvlášť zařadit do přepočtu (viz {@code AccountService}) —
   * bulk DELETE sám žádný trigger na {@code agg.recompute_queue} nespustí.
   */
  @Query("SELECT DISTINCT o.product.id AS productId, o.store.id AS storeId "
      + "FROM PriceObservation o WHERE o.submitter.id = :userId")
  List<ObservationCell> findDistinctProductStoreBySubmitterId(@Param("userId") Long userId);

  @Modifying
  @Query("DELETE FROM PriceObservation o WHERE o.submitter.id = :userId")
  int deleteBySubmitterId(@Param("userId") Long userId);

  interface ObservationCell {
    Long getProductId();

    Long getStoreId();
  }
}
