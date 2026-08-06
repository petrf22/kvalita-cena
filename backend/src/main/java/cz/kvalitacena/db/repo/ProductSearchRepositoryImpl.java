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
 * Jeden nativní dotaz přes čtyři CTE: {@code matched} (dnešní fulltext přes
 * idx_product_name_fts) → {@code scoped} (agg.price_current v rozsahu filtru obchod/město) →
 * {@code totals}/{@code best} (agregáty) → {@code quality} (známky). {@code storeId}/{@code
 * city} se posílají jako parametry a porovnávají v SQL, ne konkatenují — jediné, co se skládá
 * jako text, je JOIN typ ({@code totals} musí být INNER, když je filtr aktivní, jinak by filtr
 * "Brno" vracel i zboží, které tam nikdo nezapsal) a ORDER BY, a obojí je whitelist z pevné
 * množiny (boolean / {@link ProductSort}), nikdy hodnota od uživatele.
 */
@Repository
@RequiredArgsConstructor
class ProductSearchRepositoryImpl implements ProductSearchRepository {

  private static final String CTE = """
      WITH matched AS (
        SELECT p.id, p.name FROM core.product p
        WHERE p.status = 'ACTIVE'
          AND to_tsvector('simple', p.name) @@ plainto_tsquery('simple', :query)
      ), scoped AS (
        SELECT pc.* FROM agg.price_current pc
        JOIN core.store s ON s.id = pc.store_id
        WHERE pc.product_id IN (SELECT id FROM matched)
          AND (CAST(:storeId AS BIGINT) IS NULL OR pc.store_id = CAST(:storeId AS BIGINT))
          AND (CAST(:city AS TEXT) IS NULL OR s.city = CAST(:city AS TEXT))
      ), totals AS (
        SELECT product_id, SUM(n_obs) AS n_obs_total, MAX(last_observed_at) AS last_observed_at,
               COUNT(DISTINCT store_id) AS store_count
        FROM scoped GROUP BY product_id
      ), best AS (
        -- Jen REGULAR: PROMO/CLUB_CARD by vždy vyhrálo a "nejlevnější obchod" by porovnával
        -- nesrovnatelné (docs/datovy-model.md — price_kind se nemíchá).
        SELECT DISTINCT ON (product_id) product_id, store_id, unit_price, price_amount, n_obs
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
               t.last_observed_at, q.avg_grade, COALESCE(q.rating_count, 0)
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
    nativeQuery.setParameter("storeId", criteria.storeId());
    nativeQuery.setParameter("city", criteria.city());
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
        asLong(row[8]));
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
