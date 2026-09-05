package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.OffProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OffProductRepository extends JpaRepository<OffProduct, String> {

  List<OffProduct> findByGtinIn(Collection<String> gtins);

  /**
   * Snapshoty, které nepokrývají všechny dnes podporované jazyky — fronta pro
   * {@code OffSnapshotRefreshService}. Nativně kvůli poli {@code name_locales}: operátor
   * {@code @>} ("obsahuje všechny prvky") nemá v JPQL protějšek a rozbalovat pole do IN by
   * znamenalo tahat celý katalog do paměti.
   *
   * <p>Řadí se od nejdéle nedotčených, aby se dávky střídaly a job nezacyklil na stejné
   * skupině, kdyby jí OFF odpovídat nechtěl.
   */
  @Query(value = """
      SELECT * FROM off.product
      WHERE fetch_status = 'FOUND'
        AND NOT (name_locales @> CAST(:locales AS TEXT[]))
      ORDER BY fetched_at
      LIMIT :limit
      """, nativeQuery = true)
  List<OffProduct> findMissingNameLocales(@Param("locales") String locales, @Param("limit") int limit);
}
