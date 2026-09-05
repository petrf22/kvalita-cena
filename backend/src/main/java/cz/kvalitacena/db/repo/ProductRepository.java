package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearchRepository {

  /** Detail starého sloučeného id musí umět přesměrovat na kanonický produkt. */
  @EntityGraph(attributePaths = {"mergedInto"})
  Optional<Product> findWithMergedIntoById(Long id);

  /**
   * Dotažení pro hledání dávkou — vyhýbá se N+1 na brand/category i na scope lokálního zboží
   * (ProductSummaryFields nese i catalogScope/scopeChain/scopeStore) u seznamu výsledků.
   */
  @EntityGraph(attributePaths = {"brand", "category", "scopeChain", "scopeStore", "scopeStore.chain"})
  List<Product> findWithBrandAndCategoryByIdIn(Collection<Long> ids);

  /**
   * Podobné zboží podle názvu (idx_product_name_trgm, idx_product_alias_name_trgm) — slouží
   * jako první nabídka pro bezkódový zápis (existující druhové položky nahoru) i jako povinná
   * kontrola duplicit před založením nového zboží (docs/reputace.md, "Zboží bez čárového kódu").
   *
   * <p>Skóre je MAXIMUM ze {@code similarity} a {@code word_similarity}. Samotná
   * {@code similarity} počítá podíl shodných trigramů vůči CELÉMU názvu, takže dlouhý název
   * krátký dotaz utopí ("polevku" proti "drstkova polevka s kroupami" spadne pod práh, i když
   * je to zjevná shoda). {@code word_similarity} hledá dotaz jen v nejlepším souvislém úseku
   * názvu, což je přesně případ "napsal jsem jedno slovo z názvu" — a u bezkódového zboží,
   * kde si název vymýšlí člověk, je to ten častější případ. Obě funkce jedou po témže GIN
   * {@code gin_trgm_ops} indexu, žádná migrace navíc.
   *
   * <p>Porovnává se s názvem v KTERÉMKOLI jazyce (vedle primárního názvu a varianty z OFF
   * i komunitní překlady {@code core.product_name} a jazykové varianty {@code off.product_name})
   * — duplicita je duplicita i tehdy, když ji zakládající vidí česky a existující položka je
   * v katalogu pod německým názvem. Jinak by vícejazyčnost duplicity naopak plodila.
   *
   * <p>Nepotvrzené (DRAFT) položky se řadí AŽ ZA potvrzené. Vidět musí být, jinak by je neměl
   * kdo potvrdit (docs/reputace.md, "Práh důvěry pro zveřejnění nového záznamu" — najít je
   * cíleně musí jít i jiným přispěvatelům), ale nepotvrzený název nemá stát nad zavedeným.
   */
  @Query(value = """
      SELECT p.* FROM core.product p
      LEFT JOIN core.product_code pc ON pc.product_id=p.id AND pc.code_type='GTIN'
      LEFT JOIN off.product op ON op.gtin=pc.code AND op.fetch_status='FOUND'
      LEFT JOIN core.product_alias pa ON pa.product_id=p.id
        AND (pa.status='ACTIVE' OR EXISTS (SELECT 1 FROM core.product_alias_confirmation pac
             WHERE pac.alias_id=pa.id AND pac.user_id=:viewerId))
      LEFT JOIN core.product_name pn ON pn.product_id=p.id
      LEFT JOIN off.product_name opn ON opn.gtin=op.gtin
      WHERE p.status IN ('ACTIVE', 'DRAFT')
      AND (:storeId IS NULL OR p.catalog_scope IN ('GLOBAL', 'LEGACY_GLOBAL')
        OR (p.catalog_scope='STORE' AND p.scope_store_id=:storeId)
        OR (p.catalog_scope='CHAIN' AND p.scope_chain_id=(SELECT s.chain_id FROM core.store s WHERE s.id=:storeId)))
      AND (GREATEST(similarity(core.norm_text(COALESCE(op.product_name,p.name)), core.norm_text(:name)),
                    word_similarity(core.norm_text(:name), core.norm_text(COALESCE(op.product_name,p.name)))) > :threshold
        OR GREATEST(similarity(core.norm_text(pa.name), core.norm_text(:name)),
                    word_similarity(core.norm_text(:name), core.norm_text(pa.name))) > :threshold
        OR GREATEST(similarity(core.norm_text(pn.name), core.norm_text(:name)),
                    word_similarity(core.norm_text(:name), core.norm_text(pn.name))) > :threshold
        OR GREATEST(similarity(core.norm_text(opn.name), core.norm_text(:name)),
                    word_similarity(core.norm_text(:name), core.norm_text(opn.name))) > :threshold)
      GROUP BY p.id, op.product_name
      ORDER BY CASE WHEN p.scope_store_id=:storeId THEN 0
        WHEN p.scope_chain_id=(SELECT s.chain_id FROM core.store s WHERE s.id=:storeId) THEN 1
        WHEN p.catalog_scope='GLOBAL' THEN 2 ELSE 3 END,
      p.is_generic DESC,
      CASE WHEN p.status='DRAFT' THEN 1 ELSE 0 END,
      GREATEST(
        GREATEST(similarity(core.norm_text(COALESCE(op.product_name,p.name)), core.norm_text(:name)),
                 word_similarity(core.norm_text(:name), core.norm_text(COALESCE(op.product_name,p.name)))),
        COALESCE(MAX(GREATEST(similarity(core.norm_text(pa.name), core.norm_text(:name)),
                              word_similarity(core.norm_text(:name), core.norm_text(pa.name)))), 0),
        COALESCE(MAX(GREATEST(similarity(core.norm_text(pn.name), core.norm_text(:name)),
                              word_similarity(core.norm_text(:name), core.norm_text(pn.name)))), 0),
        COALESCE(MAX(GREATEST(similarity(core.norm_text(opn.name), core.norm_text(:name)),
                              word_similarity(core.norm_text(:name), core.norm_text(opn.name)))), 0)) DESC
      LIMIT :limit
      """, nativeQuery = true)
  List<Product> findSimilarByName(@Param("name") String name, @Param("storeId") Long storeId,
      @Param("viewerId") Long viewerId, @Param("threshold") double threshold, @Param("limit") int limit);

  /**
   * Celá lokální nabídka jedné provozovny BEZ zadaného názvu — aby uživatel viděl, co v obchodě
   * už je, dřív než začne vymýšlet vlastní název. Duplicity u bezkódového zboží nevznikají ani
   * tak překlepy jako tím, že člověk nemá co odklepnout: kdo nic nevidí, napíše "dršťkovku"
   * vedle existující "dršťkové polévky" (docs/reputace.md, "Zboží bez čárového kódu").
   *
   * <p>Řadí podle toho, jak často se cena zapisuje v TÉTO provozovně — co lidé kupují nejčastěji,
   * mají nahoře. Zamítnuté observace se nepočítají, ať moderované ceny nenafukují pořadí.
   * DRAFT položky jdou dospod ze stejného důvodu jako v {@link #findSimilarByName}.
   */
  @Query(value = """
      SELECT p.* FROM core.product p
      LEFT JOIN core.price_observation po ON po.product_id=p.id AND po.store_id=:storeId
        AND po.status='ACTIVE'
      WHERE p.status IN ('ACTIVE', 'DRAFT') AND p.is_generic
      AND ((p.catalog_scope='STORE' AND p.scope_store_id=:storeId)
        OR (p.catalog_scope='CHAIN' AND p.scope_chain_id=(SELECT s.chain_id FROM core.store s WHERE s.id=:storeId)))
      GROUP BY p.id
      ORDER BY CASE WHEN p.status='DRAFT' THEN 1 ELSE 0 END,
      COUNT(po.id) DESC, MAX(po.observed_at) DESC NULLS LAST, p.created_at DESC
      LIMIT :limit
      """, nativeQuery = true)
  List<Product> findLocalByStore(@Param("storeId") Long storeId, @Param("limit") int limit);

  /**
   * Dvojice podezřele podobných bezkódových položek v TÉMŽE obchodním rozsahu a kategorii —
   * podklad pro moderátorskou frontu duplicit. Duplicity nikdo nenahlásí (jsou neškodné, jen
   * matoucí), takže by je jinak nic neodhalilo; protože je rozsah jedna provozovna nebo jeden
   * řetězec, je self-join levný a přesný.
   *
   * <p>Měří se SYMETRICKOU {@code similarity}, ne {@code word_similarity} jako u našeptávače:
   * tam se poměřuje krátký dotaz proti dlouhému názvu, tady dva rovnocenné názvy proti sobě.
   * Sloučení zůstává vždy ruční (docs/reputace.md, "Zboží bez čárového kódu" — automatické
   * slučování podle podobnosti se vědomě nedělá).
   */
  @Query(value = """
      SELECT p1.id AS leftId, p2.id AS rightId,
        similarity(core.norm_text(p1.name), core.norm_text(p2.name)) AS score
      FROM core.product p1
      JOIN core.product p2 ON p2.id > p1.id
        AND p2.catalog_scope = p1.catalog_scope
        AND COALESCE(p2.scope_chain_id, 0) = COALESCE(p1.scope_chain_id, 0)
        AND COALESCE(p2.scope_store_id, 0) = COALESCE(p1.scope_store_id, 0)
        AND p2.category_id = p1.category_id
      WHERE p1.is_generic AND p2.is_generic
        AND p1.catalog_scope IN ('CHAIN', 'STORE')
        AND p1.status IN ('ACTIVE', 'DRAFT') AND p2.status IN ('ACTIVE', 'DRAFT')
        AND p1.hidden_at IS NULL AND p2.hidden_at IS NULL
        AND similarity(core.norm_text(p1.name), core.norm_text(p2.name)) > :threshold
      ORDER BY score DESC, p1.id, p2.id
      LIMIT :limit OFFSET :offset
      """, nativeQuery = true)
  List<DuplicateCandidateRow> findDuplicateCandidates(@Param("threshold") double threshold,
      @Param("limit") int limit, @Param("offset") int offset);

  /** Celkový počet dvojic pro stránkování fronty duplicit — stejné podmínky jako dotaz výš. */
  @Query(value = """
      SELECT COUNT(*)
      FROM core.product p1
      JOIN core.product p2 ON p2.id > p1.id
        AND p2.catalog_scope = p1.catalog_scope
        AND COALESCE(p2.scope_chain_id, 0) = COALESCE(p1.scope_chain_id, 0)
        AND COALESCE(p2.scope_store_id, 0) = COALESCE(p1.scope_store_id, 0)
        AND p2.category_id = p1.category_id
      WHERE p1.is_generic AND p2.is_generic
        AND p1.catalog_scope IN ('CHAIN', 'STORE')
        AND p1.status IN ('ACTIVE', 'DRAFT') AND p2.status IN ('ACTIVE', 'DRAFT')
        AND p1.hidden_at IS NULL AND p2.hidden_at IS NULL
        AND similarity(core.norm_text(p1.name), core.norm_text(p2.name)) > :threshold
      """, nativeQuery = true)
  long countDuplicateCandidates(@Param("threshold") double threshold);

  /** "Moje příspěvky" (MyContributionsService) — vlastní založené zboží, nejnovější první. */
  @Query(value = "SELECT * FROM core.product p WHERE p.created_by_user_id = :userId "
      + "ORDER BY p.created_at DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
  List<Product> findByCreatedByUserId(@Param("userId") Long userId, @Param("limit") int limit,
      @Param("offset") int offset);

  long countByCreatedByUserId(Long userId);

  /** Kolik nepotvrzených bezkódových položek má autor otevřených — strop proti zaplevelení
   *  katalogu (docs/reputace.md, "Zboží bez čárového kódu"). */
  long countByCreatedByUserIdAndGenericAndStatus(Long userId, boolean generic, ProductStatus status);
}
