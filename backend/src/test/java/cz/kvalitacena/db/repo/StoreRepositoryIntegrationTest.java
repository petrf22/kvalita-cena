package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional
class StoreRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired
  StoreRepository stores;

  @Test
  void matchesWordsAcrossNameCityAndStreetWithoutExposingPendingStores() {
    var target = save("Lidl", "Brno", "Vídeňská 100", StoreStatus.ACTIVE, 49.18, 16.60);
    save("Lidl", "Praha", "Vídeňská 100", StoreStatus.ACTIVE, 50.0, 14.0);
    save("Lidl", "Brno", "Vídeňská 101", StoreStatus.PENDING, 49.18, 16.60);
    for (String query : new String[]{"Lidl Brno", "brno, videnska lidl", "Vídeňská 100 Brno"}) {
      assertThat(stores.searchByText(query, null, 20, 0, null)).extracting(Store::getId).containsExactly(target.getId());
      assertThat(stores.countByText(query, null, null)).isEqualTo(1);
    }
  }

  @Test
  void radiusInMetersExcludesStoresOutsideFiveHundredMeters() {
    var close = save("Blízko", "Brno", "A", StoreStatus.ACTIVE, 49.182, 16.60);
    save("Daleko", "Brno", "B", StoreStatus.ACTIVE, 49.19, 16.60);
    assertThat(stores.findNearby(49.18, 16.60, 500, null)).extracting(Store::getId).containsExactly(close.getId());
    assertThat(stores.findNearby(49.18, 16.60, 2000, null)).hasSize(2);
  }

  private Store save(String name, String city, String street, StoreStatus status, double lat, double lon) {
    return stores.saveAndFlush(Store.builder().name(name).city(city).street(street).country("CZ")
        .status(status).lat(java.math.BigDecimal.valueOf(lat)).lon(java.math.BigDecimal.valueOf(lon)).build());
  }
}
