package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ObservationStatus;
import cz.kvalitacena.db.entity.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

  List<PriceObservation> findByProductIdAndStoreIdAndStatus(Long productId, Long storeId, ObservationStatus status);

  /**
   * Kolik různých přispěvatelů zboží zatím zapsalo — rozhoduje o promoci DRAFT → ACTIVE u
   * bezkódové položky (docs/reputace.md, "Zboží bez čárového kódu"). Anonymní observace se
   * nedají mezi sebou rozlišit (submitter_id je NULL, docs/soukromi.md), proto se každá počítá
   * jako samostatný přispěvatel (COALESCE na vlastní id) — pesimistický odhad "aspoň tolik
   * různých lidí", ne přesný počet.
   */
  @Query(value = "SELECT count(DISTINCT COALESCE(submitter_id::text, id::text)) "
      + "FROM core.price_observation WHERE product_id = :productId", nativeQuery = true)
  long countDistinctContributors(@Param("productId") Long productId);

  /**
   * Obdoba {@link #countDistinctContributors}, ale pro provozovny a bez vlastních záznamů
   * autora (leave-one-out, docs/reputace.md) — jinak by si zakladatel odemkl PENDING obchod
   * sám třemi vlastními zápisy. Anonymní observace (submitter_id NULL) se počítají dál, jen
   * autorovy vlastní ne — {@code IS DISTINCT FROM} je s NULL bezpečné oproti {@code !=}.
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
}
