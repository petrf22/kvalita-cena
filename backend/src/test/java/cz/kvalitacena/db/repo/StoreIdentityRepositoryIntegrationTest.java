package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dotazy na identitu provozovny ({@code uq_store_identity}) — {@code findByIdentity} pro
 * {@code existingId} v DUPLICATE_STORE a {@code findIdentityCandidates} pro filtr nabídky
 * z OpenStreetMap. Obojí je nativní SQL nad {@code core.norm_text}, které Mockito neověří.
 *
 * <p>Regrese, které test hlídá: {@code existingId} musí ukázat na pobočku se shodnou ulicí, ne
 * na jinou pobočku téhož názvu ve stejném městě; a kandidát se musí poznat i podle obchodu bez
 * souřadnic, který {@code findNearby} nevrátí.
 */
@Testcontainers
@SpringBootTest
class StoreIdentityRepositoryIntegrationTest {

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
  private StoreRepository storeRepository;

  private Store persist(String name, String city, String street) {
    return storeRepository.saveAndFlush(Store.builder()
        .name(name)
        .city(city)
        .street(street)
        .country("CZ")
        .status(StoreStatus.ACTIVE)
        .build());
  }

  @Test
  void findByIdentityMatchesStreetAfterNormalization() {
    String name = "Albert " + UUID.randomUUID();
    persist(name, "Praha", "Národní 10");
    Store vodickova = persist(name, "Praha", "Vodičkova 1");

    assertThat(storeRepository.findByIdentity("CZ", name.toUpperCase(), "praha", "VODICKOVA  1"))
        .map(Store::getId)
        .contains(vodickova.getId());
    assertThat(storeRepository.findByIdentity("SK", name, "Praha", "Vodičkova 1")).isEmpty();
  }

  @Test
  void findByIdentityTreatsMissingStreetAsEmpty() {
    String name = "Farma " + UUID.randomUUID();
    Store bezUlice = persist(name, "Brno", null);

    assertThat(storeRepository.findByIdentity("CZ", name, "Brno", null))
        .map(Store::getId)
        .contains(bezUlice.getId());
  }

  @Test
  void identityCandidatesIgnoreCoordinatesAndNormalizeInSql() {
    String name = "Žabka " + UUID.randomUUID();
    // Bez souřadnic — findNearby ho nevrátí, uq_store_identity ale ano.
    Store bezGps = persist(name, "Ústí nad Labem", "Hlavní 1");
    persist("Jiný " + UUID.randomUUID(), "Ústí nad Labem", "Hlavní 1");

    List<Store> found = storeRepository.findIdentityCandidates(
        "{\"" + name.toUpperCase().replace("Ž", "Z") + "\",\"Něco \\\"v uvozovkách\\\"\"}",
        "{\"usti nad labem\"}");

    assertThat(found).extracting(Store::getId).containsExactly(bezGps.getId());
  }
}
