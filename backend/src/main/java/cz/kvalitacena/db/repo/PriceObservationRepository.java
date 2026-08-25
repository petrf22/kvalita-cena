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
import java.util.UUID;

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
   * Které druhy ceny už tenhle uživatel dnes u téhle dvojice (produkt, obchod) zapsal —
   * pojmenovaná pre-kontrola PŘED unikátním indexem {@code uq_price_observation_submitter_kind_
   * per_day}. Index zůstává autoritou (závod dvou souběžných requestů), tenhle dotaz existuje
   * jen proto, aby chybová hláška uměla říct KTERÝ druh ceny koliduje — z {@code
   * DataIntegrityViolationException} se to zpětně vyčíst nedá (transakce je v tu chvíli už
   * abortovaná, doptat se nejde). Nativní dotaz kvůli {@code core.day_utc} — tatáž funkce jako
   * v indexu i v {@code agg.price_daily.day}.
   */
  @Query(value = "SELECT DISTINCT price_kind FROM core.price_observation "
      + "WHERE product_id = :productId AND store_id = :storeId AND submitter_id = :submitterId "
      + "AND core.day_utc(observed_at) = core.day_utc(CAST(:observedAt AS TIMESTAMPTZ))",
      nativeQuery = true)
  List<String> findPriceKindsBySubmitterOnDay(@Param("productId") Long productId,
      @Param("storeId") Long storeId, @Param("submitterId") Long submitterId,
      @Param("observedAt") OffsetDateTime observedAt);

  /**
   * Leave-one-out obdoba {@link #countDistinctContributorsExcluding} pro zboží — rozhoduje o
   * promoci DRAFT → ACTIVE (docs/reputace.md, "Zboží bez čárového kódu", "Práh důvěry pro
   * zveřejnění nového záznamu"). Bez vyloučení autora by si zakladatel odemkl vlastní DRAFT
   * sám vlastními zápisy cen. Anonymní observace se nedají mezi sebou rozlišit (submitter_id
   * je NULL, docs/soukromi.md) — od zavedení dávkového zápisu (submitObservations, víc cen
   * z jedné cenovky jedním voláním) by "každá observace = samostatný přispěvatel" znamenalo,
   * že jedno anonymní odeslání tří druhů ceny odemkne DRAFT/PENDING jedním kliknutím. Proto se
   * fallback počítá za DEN (COALESCE na den, ne na id observace) — všechny anonymní zápisy
   * jednoho dne se počítají nejvýš jako jedno potvrzení, pořád pesimistický odhad "aspoň tolik
   * různých lidí", jen ne per řádek.
   */
  @Query(value = "SELECT count(DISTINCT COALESCE(submitter_id::text, 'anon:' || core.day_utc(observed_at)::text)) "
      + "FROM core.price_observation WHERE product_id = :productId "
      + "AND submitter_id IS DISTINCT FROM :excludingUserId", nativeQuery = true)
  long countDistinctProductContributorsExcluding(@Param("productId") Long productId,
      @Param("excludingUserId") Long excludingUserId);

  /**
   * Obdoba {@link #countDistinctProductContributorsExcluding}, ale pro provozovny (leave-one-
   * out, docs/reputace.md) — jinak by si zakladatel odemkl PENDING obchod sám třemi vlastními
   * zápisy. Anonymní observace (submitter_id NULL) se počítají dál, jen autorovy vlastní ne —
   * {@code IS DISTINCT FROM} je s NULL bezpečné oproti {@code !=}. Fallback na den, ne na id
   * observace — viz {@link #countDistinctProductContributorsExcluding}.
   */
  @Query(value = "SELECT count(DISTINCT COALESCE(submitter_id::text, 'anon:' || core.day_utc(observed_at)::text)) "
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
   * Dávková obdoba {@link #countDistinctProductContributorsExcluding} pro výpis "Moje
   * příspěvky" (MyContributionsService) — jedna stránka výpisu nemá dělat N samostatných
   * dotazů. Na rozdíl od jednořádkové varianty (kde je vylučovaný autor vždy stejný jako
   * viewer, protože {@code promoteIfConfirmed} volá se svým vlastním produktem) tady může
   * jedno zboží ve výpisu {@code myObservations} patřit JINÉMU uživateli, než je viewer
   * (viewer u něj jen zapsal cenu) — proto se vylučuje skutečný autor záznamu (JOIN na
   * {@code core.product.created_by_user_id}), ne parametr zvenčí. Bez tohohle JOINu by číslo
   * ve výpisu neodpovídalo skutečnému prahu, který používá {@code promoteIfConfirmed}.
   */
  @Query(value = "SELECT po.product_id AS id, count(DISTINCT COALESCE(po.submitter_id::text, 'anon:' || core.day_utc(po.observed_at)::text)) AS cnt "
      + "FROM core.price_observation po JOIN core.product p ON p.id = po.product_id "
      + "WHERE po.product_id IN (:productIds) "
      + "AND po.submitter_id IS DISTINCT FROM p.created_by_user_id GROUP BY po.product_id", nativeQuery = true)
  List<ContributorCount> countDistinctProductContributorsExcludingBatch(@Param("productIds") Collection<Long> productIds);

  /** Dávková obdoba {@link #countDistinctContributorsExcluding} — viz {@link #countDistinctProductContributorsExcludingBatch}. */
  @Query(value = "SELECT po.store_id AS id, count(DISTINCT COALESCE(po.submitter_id::text, 'anon:' || core.day_utc(po.observed_at)::text)) AS cnt "
      + "FROM core.price_observation po JOIN core.store s ON s.id = po.store_id "
      + "WHERE po.store_id IN (:storeIds) "
      + "AND po.submitter_id IS DISTINCT FROM s.created_by_user_id GROUP BY po.store_id", nativeQuery = true)
  List<ContributorCount> countDistinctContributorsExcludingBatch(@Param("storeIds") Collection<Long> storeIds);

  interface ContributorCount {
    Long getId();

    long getCnt();
  }

  /**
   * "Moje příspěvky" (MyContributionsService) — vlastní zapsané ceny, nejnovější první.
   * Vrácené entity mají {@code product}/{@code store} jako LAZY proxy (native query), ale
   * volající si {@code getId()} z proxy přečte bez dotazu a dotáhne obě dávkově přes
   * {@code findAllById} — žádný N+1, jen jinak rozložený než {@link
   * #findBySubmitterIdWithProductAndStore} (ten je nestránkovaný, pro GDPR export). Zmizí
   * odsud po pseudonymizaci (submitter_id NULL po 180 dnech, docs/soukromi.md) — to je záměr.
   */
  @Query(value = "SELECT * FROM core.price_observation WHERE submitter_id = :userId "
      + "ORDER BY observed_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<PriceObservation> findBySubmitterId(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  @Query("SELECT COUNT(o) FROM PriceObservation o WHERE o.submitter.id = :userId")
  long countBySubmitterId(@Param("userId") Long userId);

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
   * Výběr cen pro moderaci (ModerationService) — cenu nejde nahlásit komunitně
   * (docs/reputace.md, "Nahlášení záznamu…", nesouhlas s cenou = nový zápis, ne flag), takže
   * moderátor k ní přistupuje přímo přes autora/zboží/obchod, ne přes frontu jako u
   * core.record_flag. Filtruje se přes {@code public_uid}, NIKDY přes rendered handle
   * (docs/soukromi.md — jazykově vykreslený text "Modrý čáp #4271" se nedá spolehlivě
   * rozebrat zpátky na kanonický klíč).
   */
  @Query(value = "SELECT po.* FROM core.price_observation po "
      + "LEFT JOIN auth.app_user u ON u.id = po.submitter_id "
      + "WHERE (:authorPublicUid IS NULL OR u.public_uid = :authorPublicUid) "
      + "AND (:productId IS NULL OR po.product_id = :productId) "
      + "AND (:storeId IS NULL OR po.store_id = :storeId) "
      // id jako tiebreaker — dávkově seedovaná data mají u víc řádků identický created_at,
      // takže samotné ORDER BY created_at DESC dává Postgresu volnost vracet je v jiném pořadí
      // mezi dvěma voláními a stránkování (LIMIT/OFFSET) by řádky nedeterministicky přeskakovalo.
      + "ORDER BY po.created_at DESC, po.id DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<PriceObservation> findForModeration(@Param("authorPublicUid") UUID authorPublicUid,
      @Param("productId") Long productId, @Param("storeId") Long storeId,
      @Param("limit") int limit, @Param("offset") int offset);

  @Query(value = "SELECT count(*) FROM core.price_observation po "
      + "LEFT JOIN auth.app_user u ON u.id = po.submitter_id "
      + "WHERE (:authorPublicUid IS NULL OR u.public_uid = :authorPublicUid) "
      + "AND (:productId IS NULL OR po.product_id = :productId) "
      + "AND (:storeId IS NULL OR po.store_id = :storeId)", nativeQuery = true)
  long countForModeration(@Param("authorPublicUid") UUID authorPublicUid,
      @Param("productId") Long productId, @Param("storeId") Long storeId);
}
