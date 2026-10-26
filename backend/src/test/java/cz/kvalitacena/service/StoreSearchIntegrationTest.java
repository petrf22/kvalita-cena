package cz.kvalitacena.service;

import cz.kvalitacena.controller.StoreSearchResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Víceslovné hledání obchodů ({@code searchStores}) — každé slovo dotazu musí být v názvu, městě
 * nebo ulici ({@link StoreRepository#TEXT_MATCH}). Nativní SQL nad {@code core.norm_text}, které
 * Mockito neověří, proto Testcontainers.
 *
 * <p>Regrese, které test hlídá: „Lidl Brno" dřív nenašlo nic, protože se celý dotaz porovnával
 * s názvem NEBO městem; počet ({@code totalCount}) musí sedět na seznam; a výraz prohledávaného
 * textu se musí shodovat s {@code idx_store_search_trgm}, jinak hledání tiše přejde na sekvenční
 * průchod.
 */
@Testcontainers
@SpringBootTest
@Transactional
class StoreSearchIntegrationTest {

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
  private StoreSearchService storeSearchService;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private AppUserRepository appUserRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void wordsMatchAcrossNameCityAndStreetInAnyOrder() {
    Store target = persist("Lidl", "Brno", "Vídeňská 100", StoreStatus.ACTIVE, null);
    persist("Lidl", "Praha", "Vídeňská 100", StoreStatus.ACTIVE, null);
    persist("Albert", "Brno", "Vídeňská 100", StoreStatus.ACTIVE, null);

    for (String query : List.of("Lidl Brno", "brno, videnska lidl", "Vídeňská 100 Brno Lidl", "  LIDL   brno ")) {
      StoreSearchResult result = storeSearchService.search(query, null, 20, 0, null);
      assertThat(result.items()).as(query).extracting(Store::getId).containsExactly(target.getId());
      assertThat(result.totalCount()).as(query).isEqualTo(1);
    }
    assertThat(storeSearchService.search("Lidl Ostrava", null, 20, 0, null).items()).isEmpty();
  }

  @Test
  void singleWordStillMatchesNameOrCity() {
    Store brnoLidl = persist("Lidl", "Brno", null, StoreStatus.ACTIVE, null);
    Store prahaLidl = persist("Lidl", "Praha", null, StoreStatus.ACTIVE, null);
    Store brnoAlbert = persist("Albert", "Brno", null, StoreStatus.ACTIVE, null);

    assertThat(storeSearchService.search("lidl", null, 20, 0, null).items())
        .extracting(Store::getId).containsExactlyInAnyOrder(brnoLidl.getId(), prahaLidl.getId());
    assertThat(storeSearchService.search("brno", null, 20, 0, null).items())
        .extracting(Store::getId).containsExactlyInAnyOrder(brnoLidl.getId(), brnoAlbert.getId());
  }

  @Test
  void pendingStoreIsVisibleOnlyToItsAuthor() {
    AppUser author = persistUser();
    Store pending = persist("Pekárna U Nováků", "Brno", "Husova 5", StoreStatus.PENDING, author.getId());

    assertThat(storeSearchService.search("pekarna brno", null, 20, 0, null).items()).isEmpty();
    StoreSearchResult own = storeSearchService.search("pekarna brno", null, 20, 0, author.getId());
    assertThat(own.items()).extracting(Store::getId).containsExactly(pending.getId());
    assertThat(own.totalCount()).isEqualTo(1);
  }

  @Test
  void longestWordIsAlwaysInsideSomeSqlWord() {
    assertThat(StoreSearchService.longestWord("Lidl, Vídeňská 100")).isEqualTo("Vídeňská");
    // Nezlomitelná mezera: SQL ji po core.norm_text bere jako oddělovač, Java proto taky —
    // jinak by předfiltr hledal „lidl brno" vcelku a obchod by nenašel.
    assertThat(StoreSearchService.longestWord("Lidl Brno")).isEqualTo("Lidl");
    assertThat(StoreSearchService.longestWord(",")).isEmpty();
    assertThat(StoreSearchService.longestWord(null)).isNull();
  }

  @Test
  void searchTextMatchesTrigramIndexExpression() {
    // S vypnutým sekvenčním průchodem musí plán sáhnout po idx_store_search_trgm — kdyby se
    // StoreRepository.SEARCH_TEXT a výraz indexu rozešly, index by tu nebyl k mání.
    List<String> plan = jdbcTemplate.execute((ConnectionCallback<List<String>>) connection -> {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL enable_seqscan = off");
        List<String> lines = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("EXPLAIN SELECT 1 FROM core.store s WHERE "
            + StoreRepository.SEARCH_TEXT + " LIKE '%lidl%'")) {
          while (rs.next()) {
            lines.add(rs.getString(1));
          }
        }
        return lines;
      }
    });
    assertThat(String.join("\n", plan)).contains("idx_store_search_trgm");
  }

  private Store persist(String name, String city, String street, StoreStatus status, Long createdBy) {
    return storeRepository.saveAndFlush(Store.builder()
        .name(name)
        .city(city)
        .street(street)
        .country("CZ")
        .status(status)
        .createdByUserId(createdBy)
        .build());
  }

  private AppUser persistUser() {
    String unique = UUID.randomUUID().toString();
    return appUserRepository.saveAndFlush(AppUser.builder()
        .publicUid(UUID.randomUUID())
        .emailHash(unique.getBytes())
        .emailEnc(unique.getBytes())
        .publicHandle(unique.substring(0, 30))
        .handleAdjective("blue")
        .handleNoun("stork")
        .handleNumber((short) 1)
        .status(AppUserStatus.ACTIVE)
        .build());
  }
}
