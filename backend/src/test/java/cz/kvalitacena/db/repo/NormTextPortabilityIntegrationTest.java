package cz.kvalitacena.db.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code core.norm_text} musí přežít obnovu zálohy na čistou instanci
 * ({@code db/changelog/2026-10-28/01-norm-text-portable.yaml}). Dřívější definice měla v těle
 * natvrdo numerický OID slovníku unaccent — v databázi, kde vznikla, fungovala bez chyby, takže
 * ji žádný jiný test nechytil, a selhala až při obnově produkční zálohy na nový server.
 *
 * <p>Druhý test hlídá past, kvůli které OID původně vznikl: inlining funkce při CREATE INDEX
 * s prázdným search_path (tak obnovuje pg_dump) nesmí selhat na nedohledaném slovníku.
 */
@Testcontainers
@SpringBootTest
class NormTextPortabilityIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void functionBodyReferencesDictionaryByNameNotOid() {
    String body = jdbcTemplate.queryForObject("""
        SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'core' AND p.proname = 'norm_text'
        """, String.class);

    assertThat(body)
        .doesNotContainPattern("'\\d+'::regdictionary")
        .contains(".unaccent'::regdictionary");
  }

  @Test
  void expressionIndexBuildsWithEmptySearchPath() {
    String normalized = jdbcTemplate.execute((ConnectionCallback<String>) con -> {
      boolean autoCommit = con.getAutoCommit();
      con.setAutoCommit(false);
      try (Statement st = con.createStatement()) {
        st.execute("SET LOCAL search_path = ''");
        st.execute("CREATE TABLE pg_temp.norm_text_probe (t text)");
        st.execute("INSERT INTO pg_temp.norm_text_probe VALUES ('  Kuřecí   PRSNÍ řízky ')");
        st.execute("CREATE INDEX ON pg_temp.norm_text_probe (core.norm_text(t))");
        try (ResultSet rs = st.executeQuery(
            "SELECT core.norm_text(t) FROM pg_temp.norm_text_probe")) {
          rs.next();
          return rs.getString(1);
        }
      } finally {
        con.rollback();
        con.setAutoCommit(autoCommit);
      }
    });

    assertThat(normalized).isEqualTo("kureci prsni rizky");
  }
}
