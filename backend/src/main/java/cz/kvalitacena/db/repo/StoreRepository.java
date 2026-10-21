package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

  /**
   * {@code earth_box} nejdřív ořízne kandidáty přes GiST index ({@code idx_store_geo}),
   * {@code earth_distance} pak dopočítá přesnou vzdálenost — standardní vzor pro cube/
   * earthdistance (docs/datovy-model.md, "cube/earthdistance místo PostGIS"). Filtr vzdálenosti
   * pracuje VŽDY jen s globálními souřadnicemi core.store.lat/lon, i pro PENDING provozovnu
   * autora — patch (core.store_user_edit) smí ovlivnit jen zobrazenou hodnotu, jinak by
   * přestal platit GiST index nad idx_store_geo (viz StoreOverlayService).
   *
   * <p>Poloha uživatele sem vchází jen jako parametry dotazu, nikam se neukládá
   * (docs/soukromi.md). Viditelnost pod prahem důvěry: PENDING provozovnu vidí jen autor
   * (docs/reputace.md).
   */
  @Query(value = "SELECT * FROM core.store s WHERE "
      + "(s.status = 'ACTIVE' OR (s.status = 'PENDING' AND s.created_by_user_id = CAST(:viewerId AS BIGINT))) "
      + "AND s.hidden_at IS NULL "
      + "AND s.lat IS NOT NULL AND s.lon IS NOT NULL "
      + "AND earth_box(ll_to_earth(:lat, :lon), :radiusMeters) @> ll_to_earth(s.lat, s.lon) "
      + "AND earth_distance(ll_to_earth(:lat, :lon), ll_to_earth(s.lat, s.lon)) <= :radiusMeters "
      + "ORDER BY earth_distance(ll_to_earth(:lat, :lon), ll_to_earth(s.lat, s.lon))",
      nativeQuery = true)
  List<Store> findNearby(@Param("lat") double lat, @Param("lon") double lon,
      @Param("radiusMeters") double radiusMeters, @Param("viewerId") Long viewerId);

  /**
   * Našeptávač obchodů podle názvu NEBO města (idx_store_name_trgm/idx_store_city_trgm) —
   * doplněk k {@link #findNearby}, pro zápis ceny bez sdílení polohy nebo zpětně z domova
   * (docs/datovy-model.md, "Identita provozovny"). Jedno textové pole v UI musí najít obchod
   * napsáním buď jeho jména, nebo města, proto {@code query} matchuje OBOJÍ (ne jen název) —
   * {@code city} zůstává samostatný nepovinný přesný filtr navíc, kdyby ho někdy klient chtěl
   * poslat odděleně (v etapě 1 to žádný klient nedělá, oba parametry se prostě neuplatní,
   * když jsou null).
   */
  @Query(value = "SELECT * FROM core.store s WHERE "
      + "(s.status = 'ACTIVE' OR (s.status = 'PENDING' AND s.created_by_user_id = CAST(:viewerId AS BIGINT))) "
      + "AND s.hidden_at IS NULL "
      + "AND (:query IS NULL OR core.norm_text(s.name) LIKE '%' || core.norm_text(:query) || '%' "
      + "     OR core.norm_text(s.city) LIKE '%' || core.norm_text(:query) || '%') "
      + "AND (:city IS NULL OR core.norm_text(s.city) = core.norm_text(:city)) "
      + "ORDER BY CASE WHEN :query IS NULL THEN 0 ELSE "
      + "  GREATEST(similarity(core.norm_text(s.name), core.norm_text(:query)), "
      + "           similarity(core.norm_text(s.city), core.norm_text(:query))) END DESC, "
      + "         s.name "
      + "LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<Store> searchByText(@Param("query") String query, @Param("city") String city,
      @Param("limit") int limit, @Param("offset") int offset, @Param("viewerId") Long viewerId);

  @Query(value = "SELECT count(*) FROM core.store s WHERE "
      + "(s.status = 'ACTIVE' OR (s.status = 'PENDING' AND s.created_by_user_id = CAST(:viewerId AS BIGINT))) "
      + "AND s.hidden_at IS NULL "
      + "AND (:query IS NULL OR core.norm_text(s.name) LIKE '%' || core.norm_text(:query) || '%' "
      + "     OR core.norm_text(s.city) LIKE '%' || core.norm_text(:query) || '%') "
      + "AND (:city IS NULL OR core.norm_text(s.city) = core.norm_text(:city))",
      nativeQuery = true)
  long countByText(@Param("query") String query, @Param("city") String city, @Param("viewerId") Long viewerId);

  /**
   * "Našli jsme podobné" kontrola před založením nové provozovny — nezávisle na
   * {@code uq_store_identity} (ta chytí jen přesnou shodu po normalizaci), aby uživatel
   * viděl i blízké, ne identické záznamy.
   */
  @Query(value = "SELECT * FROM core.store s WHERE s.status = 'ACTIVE' "
      + "AND core.norm_text(s.city) = core.norm_text(:city) "
      + "AND similarity(core.norm_text(s.name), core.norm_text(:name)) > 0.3 "
      + "ORDER BY similarity(core.norm_text(s.name), core.norm_text(:name)) DESC "
      + "LIMIT 5", nativeQuery = true)
  List<Store> findSimilar(@Param("name") String name, @Param("city") String city);

  /**
   * Provozovna, se kterou se zakládaný obchod srazil na {@code uq_store_identity} — predikát je
   * přesně výraz toho indexu (country, normalizovaný název, město a ulice, bez MERGED), aby
   * {@code existingId} v chybě DUPLICATE_STORE ukazoval na skutečnou kolizi, ne na první
   * podobný název z {@link #findSimilar} (ten může být jiná pobočka ve stejném městě).
   */
  @Query(value = "SELECT * FROM core.store s WHERE s.status <> 'MERGED' "
      + "AND s.country = :country "
      + "AND core.norm_text(s.name) = core.norm_text(:name) "
      + "AND core.norm_text(s.city) = core.norm_text(:city) "
      + "AND COALESCE(core.norm_text(s.street), '') = COALESCE(core.norm_text(CAST(:street AS TEXT)), '') "
      + "LIMIT 1", nativeQuery = true)
  Optional<Store> findByIdentity(@Param("country") String country, @Param("name") String name,
      @Param("city") String city, @Param("street") String street);

  /**
   * Obchody, se kterými by se kandidáti z nabídky OpenStreetMap mohli srazit na
   * {@code uq_store_identity} — podle normalizovaného názvu a města, BEZ ohledu na souřadnice
   * a viditelnost. {@link #findNearby} totiž nevrátí provozovnu založenou bez GPS ani cizí
   * PENDING, a ty by jinak v nabídce zůstaly a skončily až na DUPLICATE_STORE. Přesnou shodu
   * (země, ulice) dořeší {@code OsmStoreCandidateFilter}.
   *
   * <p>{@code names}/{@code cities} jsou literály pole PostgreSQL ({@code {"a","b"}}, viz
   * {@code StoreGraphQlController}) — normalizace musí proběhnout v SQL přes
   * {@code core.norm_text}, ne v Javě, jinak by se s indexem rozcházela.
   */
  @Query(value = "SELECT * FROM core.store s WHERE s.status <> 'MERGED' "
      + "AND core.norm_text(s.name) IN (SELECT core.norm_text(n) FROM unnest(CAST(:names AS TEXT[])) n) "
      + "AND core.norm_text(s.city) IN (SELECT core.norm_text(c) FROM unnest(CAST(:cities AS TEXT[])) c)",
      nativeQuery = true)
  List<Store> findIdentityCandidates(@Param("names") String names, @Param("cities") String cities);

  /**
   * Číselník pro filtr hledání (searchFacets) — přes idx_store_city, jen obchody se skutečnou
   * cenou. Filtr na zemi (docs/lokalizace.md) — bez něj by dropdown měst míchal ČR se SK/PL.
   */
  @Query(value = "SELECT DISTINCT s.city FROM core.store s "
      + "JOIN agg.price_current pc ON pc.store_id = s.id "
      + "WHERE s.status = 'ACTIVE' AND s.country = :country ORDER BY s.city", nativeQuery = true)
  List<String> findDistinctCitiesWithPrices(@Param("country") String country);

  /** Obchody se skutečnou cenou v dané zemi, pro dropdown filtru hledání. */
  @Query(value = "SELECT DISTINCT s.* FROM core.store s "
      + "JOIN agg.price_current pc ON pc.store_id = s.id "
      + "WHERE s.status = 'ACTIVE' AND s.country = :country ORDER BY s.name", nativeQuery = true)
  List<Store> findDistinctWithPrices(@Param("country") String country);

  /** "Moje příspěvky" (MyContributionsService) — vlastní založené provozovny, nejnovější první. */
  @Query(value = "SELECT * FROM core.store s WHERE s.created_by_user_id = :userId "
      + "ORDER BY s.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Store> findByCreatedByUserId(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  long countByCreatedByUserId(Long userId);

  /**
   * Návrh obchodu pro hlavičku účtenky (ReceiptMatchingService) — IČO z účtenky identifikuje
   * provozovatele (u řetězce stejné pro všechny pobočky), rozhodne až adresa. Bez IČO se
   * hledá podle názvu a adresy. Vrací kandidáty seřazené, práh posuzuje volající.
   */
  @Query(value = """
      SELECT s.* FROM core.store s
      WHERE s.status IN ('ACTIVE', 'PENDING') AND s.hidden_at IS NULL
        AND ((CAST(:ico AS TEXT) IS NOT NULL AND s.ico = CAST(:ico AS TEXT))
          OR (CAST(:name AS TEXT) IS NOT NULL
              AND word_similarity(core.norm_text(s.name), core.norm_text(CAST(:name AS TEXT))) > 0.5))
      ORDER BY word_similarity(core.norm_text(COALESCE(s.street, '') || ' ' || s.city),
                               core.norm_text(COALESCE(CAST(:address AS TEXT), ''))) DESC,
               CASE WHEN s.ico = CAST(:ico AS TEXT) THEN 0 ELSE 1 END
      LIMIT 5
      """, nativeQuery = true)
  List<Store> findReceiptCandidates(@Param("ico") String ico, @Param("name") String name,
      @Param("address") String address);

  @Query(value = "SELECT word_similarity(core.norm_text(COALESCE(s.street, '') || ' ' || s.city), "
      + "core.norm_text(:address)) FROM core.store s WHERE s.id = :storeId", nativeQuery = true)
  Double addressSimilarity(@Param("storeId") Long storeId, @Param("address") String address);
}
