package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

  /** Číselník pro filtr hledání (searchFacets) — přes idx_store_city, jen obchody se skutečnou cenou. */
  @Query(value = "SELECT DISTINCT s.city FROM core.store s "
      + "JOIN agg.price_current pc ON pc.store_id = s.id "
      + "WHERE s.status = 'ACTIVE' ORDER BY s.city", nativeQuery = true)
  List<String> findDistinctCitiesWithPrices();

  /** Obchody se skutečnou cenou, pro dropdown filtru hledání. */
  @Query(value = "SELECT DISTINCT s.* FROM core.store s "
      + "JOIN agg.price_current pc ON pc.store_id = s.id "
      + "WHERE s.status = 'ACTIVE' ORDER BY s.name", nativeQuery = true)
  List<Store> findDistinctWithPrices();
}
