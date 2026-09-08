package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RetailChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RetailChainRepository extends JpaRepository<RetailChain, Long> {

  /**
   * Našeptávač řetězců podle názvu při zakládání obchodu (docs/stav-implementace.md, "výběr
   * řetězce při zakládání obchodu") — fixní kurátorský číselník naplněný migrací
   * (2026-08-29/03-retail-chain-seed.yaml), stejný {@code core.norm_text(...) LIKE} vzor jako
   * {@link StoreRepository#searchByText} kvůli diakritice a velikosti písmen.
   */
  @Query(value = "SELECT * FROM core.retail_chain c WHERE c.country = :country "
      + "AND (:query IS NULL OR core.norm_text(c.name) LIKE '%' || core.norm_text(:query) || '%') "
      + "ORDER BY c.name LIMIT :limit", nativeQuery = true)
  List<RetailChain> searchByText(@Param("query") String query, @Param("country") String country,
      @Param("limit") int limit);

  /**
   * Řetězec podle slugu z hlavičky účtenky. Vždy se zemí — unikát je {@code (country, slug)},
   * protože tentýž slug může v cílových 16 zemích (docs/lokalizace.md) patřit jinému subjektu.
   */
  Optional<RetailChain> findFirstBySlugAndCountry(String slug, String country);
}
