package cz.kvalitacena.db.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Jeden nativní dotaz přes sedm CTE: {@code q} (normalizace dotazu) → {@code sel_scope}/
 * {@code cat_hit}/{@code cat_scope} (kategorie) → {@code candidate} (čtyřvětvý UNION kandidátů)
 * → {@code matched} (viditelnost + explicitní filtr kategorie) → {@code scoped} (agg.price_current
 * v rozsahu filtru obchod/město) → {@code totals}/{@code best} (agregáty) → {@code quality}
 * (známky). {@code storeId}/{@code city}/{@code categoryId}/{@code viewerId} se posílají jako
 * parametry a porovnávají v SQL, ne konkatenují — jediné, co se skládá jako text, je JOIN typ
 * ({@code totals} musí být INNER, když je filtr aktivní, jinak by filtr "Brno" vracel i zboží,
 * které tam nikdo nezapsal) a ORDER BY, a obojí je whitelist z pevné množiny (boolean /
 * {@link ProductSort}), nikdy hodnota od uživatele.
 *
 * <p>{@code candidate} je čtyřvětvý UNION (název zboží, uživatelský patch názvu, kategorie,
 * čárový kód), NE jeden OR přes čtyři tabulky — OR, jehož některá strana sahá na LEFT JOINovanou
 * nebo jinak spojenou tabulku, nejde poskládat do BitmapOr a planner spadne na seq scan celého
 * katalogu. Takhle každá větev běží přes svůj vlastní index (idx_product_name_norm_fts,
 * primární klíč product_user_edit, idx_product_category_status, idx_product_code_code). UNION
 * (ne UNION ALL) sjednotí duplicity do JEDNOHO seznamu bez příznaku, kudy se položka našla.
 *
 * <p>{@code matched} nese viditelnost podle viewera (docs/datovy-model.md, "Uživatelská vrstva
 * nad globálními daty"): globální ACTIVE zboží vidí každý, vlastní DRAFT jen autor, skryté
 * (nahlášené) zboží nevidí nikdo (autor má zvlášť {@code product(id)} s příznakem) — a k tomu
 * explicitní filtr kategorie z UI ({@code categoryId}), který je AND nad nalezenými kandidáty,
 * ne další OR větev v {@code candidate}: dotaz "mléko" s filtrem "Drogerie" musí vrátit nic, ne
 * celou drogerii.
 *
 * <p>Hledání podle kategorie (druhá větev {@code candidate}, přes {@code cat_scope}) bere celý
 * PODSTROM kategorie, jejíž lokalizovaný název nebo slug se shoduje s dotazem — "bio 3,5 % tuku"
 * v kategorii "Mléko" se najde na "mléko", i když to slovo v názvu nemá. {@code cat_hit}
 * matchuje přesně ten název, který uživatel v appce vidí ({@code COALESCE(i18n.name, c.name)}
 * pro locale requestu — stejný výraz jako {@code ProductGraphQlController.categoryName}) plus
 * jazykově neutrální {@code slug}, substringem po slovech (ne {@code plainto_tsquery} — číselník
 * je plurálový a "simple" konfigurace nemá stemmer, takže by slovní shoda na "sýr" nesedla).
 * {@code cat_scope} rozšiřuje na podstrom přes {@code path = X OR path LIKE X || '/%'} —
 * NIKDY {@code LIKE X || '%'}, to by pod "potraviny/mlecne" schovalo i hypotetickou
 * "potraviny/mlecne-nahrazky". Explicitní filtr ({@code sel_scope}) používá tutéž hranici.
 *
 * <p>Čtvrtá větev hledá podle čárového kódu ({@code codeQuery}, GTIN-14 normalizace z
 * {@link cz.kvalitacena.service.ProductSearchService}) — přes {@code core.product_code} s
 * {@code code_type = 'GTIN'} JEN, přes existující {@code idx_product_code_code}. Nikdy
 * {@code STORE_INTERNAL} — vnitroobchodní kódy váhového zboží mají povinný {@code chain_id}
 * a nejsou globální identifikátor (kořenový CLAUDE.md), takže by přes ně hledání napříč obchody
 * dávalo nesmyslné shody.
 */
@Repository
@RequiredArgsConstructor
class ProductSearchRepositoryImpl implements ProductSearchRepository {

  private static final String CTE = """
      WITH q AS (
        -- Jediné místo, kde se dotaz normalizuje (core.norm_text: trim → jedna mezera →
        -- unaccent → lower) — dál se pracuje výhradně s nq/tsq, nikdy se syrovým :query.
        SELECT core.norm_text(CAST(:query AS TEXT)) AS nq,
               plainto_tsquery('simple', core.norm_text(CAST(:query AS TEXT))) AS tsq
      ), sel_scope AS (
        -- Explicitní filtr kategorie z UI: vybraná kategorie VČETNĚ podstromu. Rozšíření běží
        -- v SQL z :categoryId, ne z path poslané z Javy — path tím nikdy neopustí DB.
        SELECT c.id FROM core.category c
        WHERE CAST(:categoryId AS BIGINT) IS NOT NULL
          AND EXISTS (SELECT 1 FROM core.category s
                      WHERE s.id = CAST(:categoryId AS BIGINT)
                        AND (c.path = s.path OR c.path LIKE s.path || '/%'))
      ), cat_hit AS (
        -- Substring po slovech nad zobrazovaným názvem kategorie (locale requestu) + slug.
        -- Pojistka na délku: bez ní by "o" (a prázdný dotaz) vrátily celý katalog.
        SELECT c.path FROM core.category c
        LEFT JOIN core.category_i18n ci
          ON ci.category_id = c.id AND ci.locale = CAST(:locale AS TEXT)
        CROSS JOIN q
        WHERE length(q.nq) >= 3
          AND NOT EXISTS (
            SELECT 1 FROM unnest(string_to_array(q.nq, ' ')) AS w(word)
            WHERE strpos(core.norm_text(COALESCE(ci.name, c.name)), w.word) = 0
              AND strpos(replace(c.slug, '-', ' '), w.word) = 0)
      ), cat_scope AS (
        SELECT c.id FROM core.category c
        WHERE EXISTS (SELECT 1 FROM cat_hit h WHERE c.path = h.path OR c.path LIKE h.path || '/%')
      ), candidate AS (
          SELECT p.id FROM core.product p
          WHERE to_tsvector('simple', core.norm_text(p.name)) @@ (SELECT tsq FROM q)
        UNION
          SELECT e.product_id FROM core.product_user_edit e
          WHERE e.user_id = CAST(:viewerId AS BIGINT)
            AND to_tsvector('simple', core.norm_text(e.name)) @@ (SELECT tsq FROM q)
        UNION
          SELECT pc.product_id FROM off.product op
          JOIN core.product_code pc ON pc.code = op.gtin AND pc.code_type = 'GTIN'
          WHERE op.fetch_status = 'FOUND'
            AND to_tsvector('simple', core.norm_text(op.product_name)) @@ (SELECT tsq FROM q)
        UNION
          SELECT p.id FROM core.product p WHERE p.category_id IN (SELECT id FROM cat_scope)
        UNION
          SELECT pc.product_id FROM off.product op
          JOIN core.category oc ON oc.slug = op.mapped_category_slug
          JOIN core.product_code pc ON pc.code = op.gtin AND pc.code_type = 'GTIN'
          WHERE op.fetch_status = 'FOUND' AND oc.id IN (SELECT id FROM cat_scope)
        UNION
          SELECT pc2.product_id FROM core.product_code pc2
          WHERE CAST(:codeQuery AS TEXT) IS NOT NULL
            AND pc2.code = CAST(:codeQuery AS TEXT) AND pc2.code_type = 'GTIN'
      ), matched AS (
        SELECT p.id, COALESCE(e.name, op.product_name, p.name) AS name
        FROM core.product p
        JOIN candidate cnd ON cnd.id = p.id
        LEFT JOIN core.product_user_edit e
          ON e.product_id = p.id AND e.user_id = CAST(:viewerId AS BIGINT)
        LEFT JOIN LATERAL (
          SELECT pc.code FROM core.product_code pc
          WHERE pc.product_id = p.id AND pc.code_type = 'GTIN'
          ORDER BY pc.is_primary DESC, pc.id ASC LIMIT 1
        ) gt ON TRUE
        LEFT JOIN off.product op ON op.gtin = gt.code AND op.fetch_status = 'FOUND'
        LEFT JOIN core.category oc ON oc.slug = op.mapped_category_slug
        WHERE (p.status = 'ACTIVE'
               OR (p.status = 'DRAFT' AND p.created_by_user_id = CAST(:viewerId AS BIGINT)))
          AND p.hidden_at IS NULL
          AND (CAST(:categoryId AS BIGINT) IS NULL
               OR COALESCE(e.category_id, oc.id, p.category_id) IN (SELECT id FROM sel_scope))
      ), scoped AS (
        -- country je NIKDY null v praxi (ProductGraphQlController.resolveCountry vždy dosadí
        -- konkrétní zemi) — díky tomu je scoped už jednoměnový a "best" níž smí bezpečně
        -- porovnávat unit_price napříč řádky, aniž by míchal CZK s PLN (docs/lokalizace.md).
        SELECT pc.* FROM agg.price_current pc
        JOIN core.store s ON s.id = pc.store_id
        WHERE pc.product_id IN (SELECT id FROM matched)
          AND s.country = :country
          AND (CAST(:storeId AS BIGINT) IS NULL OR pc.store_id = CAST(:storeId AS BIGINT))
          AND (CAST(:city AS TEXT) IS NULL OR s.city = CAST(:city AS TEXT))
      ), totals AS (
        SELECT product_id, SUM(n_obs) AS n_obs_total, MAX(last_observed_at) AS last_observed_at,
               COUNT(DISTINCT store_id) AS store_count
        FROM scoped GROUP BY product_id
      ), best AS (
        -- Jen REGULAR: PROMO/CLUB_CARD by vždy vyhrálo a "nejlevnější obchod" by porovnával
        -- nesrovnatelné (docs/datovy-model.md — price_kind se nemíchá).
        SELECT DISTINCT ON (product_id) product_id, store_id, unit_price, price_amount, n_obs, currency
        FROM scoped WHERE price_kind = 'REGULAR' AND unit_price IS NOT NULL
        ORDER BY product_id, unit_price ASC, store_id ASC
      ), quality AS (
        SELECT product_id, ROUND(AVG(grade)::numeric, 2) AS avg_grade, COUNT(*) AS rating_count
        FROM core.product_quality_rating
        WHERE product_id IN (SELECT id FROM matched) GROUP BY product_id
      )
      """;

  private final EntityManager entityManager;

  @Override
  public List<ProductSearchRow> search(ProductSearchCriteria criteria) {
    boolean scopeActive = criteria.storeId() != null || criteria.city() != null;
    String totalsJoin = scopeActive ? "JOIN" : "LEFT JOIN";

    String sql = CTE + """
        SELECT m.id, COALESCE(t.n_obs_total, 0), b.price_amount, b.unit_price, b.store_id, b.n_obs,
               t.last_observed_at, q.avg_grade, COALESCE(q.rating_count, 0), b.currency
        FROM matched m
        """ + totalsJoin + """
         totals t  ON t.product_id = m.id
        LEFT JOIN best    b ON b.product_id = m.id
        LEFT JOIN quality q ON q.product_id = m.id
        ORDER BY\s""" + orderByFragment(criteria.sort()) + ", m.name ASC LIMIT :first OFFSET :offset";

    Query nativeQuery = entityManager.createNativeQuery(sql);
    bindCriteria(nativeQuery, criteria);
    nativeQuery.setParameter("first", criteria.first());
    nativeQuery.setParameter("offset", criteria.offset());

    @SuppressWarnings("unchecked")
    List<Object[]> rows = nativeQuery.getResultList();
    return rows.stream().map(this::toRow).toList();
  }

  @Override
  public long count(ProductSearchCriteria criteria) {
    boolean scopeActive = criteria.storeId() != null || criteria.city() != null;
    String totalsJoin = scopeActive ? "JOIN" : "LEFT JOIN";

    String sql = CTE + "SELECT COUNT(*) FROM matched m " + totalsJoin + " totals t ON t.product_id = m.id";

    Query nativeQuery = entityManager.createNativeQuery(sql);
    bindCriteria(nativeQuery, criteria);
    return ((Number) nativeQuery.getSingleResult()).longValue();
  }

  private void bindCriteria(Query nativeQuery, ProductSearchCriteria criteria) {
    nativeQuery.setParameter("query", criteria.query());
    nativeQuery.setParameter("codeQuery", criteria.codeQuery());
    nativeQuery.setParameter("storeId", criteria.storeId());
    nativeQuery.setParameter("city", criteria.city());
    nativeQuery.setParameter("categoryId", criteria.categoryId());
    nativeQuery.setParameter("country", criteria.country());
    nativeQuery.setParameter("viewerId", criteria.viewerId());
    nativeQuery.setParameter("locale", criteria.locale());
  }

  /** Whitelist fragmentů podle {@link ProductSort} — nikdy konkatenace uživatelského vstupu. */
  private String orderByFragment(ProductSort sort) {
    return switch (sort == null ? ProductSort.REPORT_COUNT : sort) {
      case REPORT_COUNT -> "COALESCE(t.n_obs_total, 0) DESC, t.last_observed_at DESC NULLS LAST";
      case PRICE_ASC -> "b.unit_price ASC NULLS LAST";
      case QUALITY -> "q.avg_grade ASC NULLS LAST"; // 1 = nejlepší
      case LAST_REPORTED -> "t.last_observed_at DESC NULLS LAST";
      case NAME -> "m.name ASC";
    };
  }

  private ProductSearchRow toRow(Object[] row) {
    return new ProductSearchRow(
        asLong(row[0]),
        asLong(row[1]),
        asBigDecimal(row[2]),
        asBigDecimal(row[3]),
        asLong(row[4]),
        asInteger(row[5]),
        asOffsetDateTime(row[6]),
        asBigDecimal(row[7]),
        asLong(row[8]),
        (String) row[9]);
  }

  private Long asLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private Integer asInteger(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private BigDecimal asBigDecimal(Object value) {
    if (value == null) return null;
    if (value instanceof BigDecimal bd) return bd;
    return new BigDecimal(value.toString());
  }

  private OffsetDateTime asOffsetDateTime(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime odt) return odt;
    if (value instanceof Instant instant) return instant.atOffset(ZoneOffset.UTC);
    if (value instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    throw new IllegalStateException("Neočekávaný typ pro DateTime: " + value.getClass());
  }
}
