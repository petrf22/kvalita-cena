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
   * earthdistance (docs/datovy-model.md, "cube/earthdistance místo PostGIS").
   *
   * <p>Poloha uživatele sem vchází jen jako parametry dotazu, nikam se neukládá
   * (docs/soukromi.md).
   */
  @Query(value = "SELECT * FROM core.store s WHERE s.status = 'ACTIVE' "
      + "AND earth_box(ll_to_earth(:lat, :lon), :radiusMeters) @> ll_to_earth(s.lat, s.lon) "
      + "AND earth_distance(ll_to_earth(:lat, :lon), ll_to_earth(s.lat, s.lon)) <= :radiusMeters "
      + "ORDER BY earth_distance(ll_to_earth(:lat, :lon), ll_to_earth(s.lat, s.lon))",
      nativeQuery = true)
  List<Store> findNearby(@Param("lat") double lat, @Param("lon") double lon,
      @Param("radiusMeters") double radiusMeters);
}
