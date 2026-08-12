package cz.kvalitacena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

/**
 * Jediné místo v repu, kde appka nastartuje CELÝ Spring kontext proti reálné Postgres DB —
 * ostatní testy (22+ tříd) testují service vrstvu čistě přes Mockito. Bez tohohle testu by se
 * {@code ddl-auto: validate} proti Liquibase schématu (včetně {@code cube}/{@code earthdistance}
 * z {@code 00-schemas.yaml}), GraphQL wiring a autorizace ze {@code SecurityConfig} poprvé
 * ověřily až při nasazení na produkční server (docs/vydani.md) — vyžaduje Docker (Testcontainers),
 * stejně jako lokální dev DB (compose.yaml).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // Appka by jinak přes spring-boot-docker-compose zkusila nastartovat i dev compose.yaml.
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @LocalServerPort
  private int port;

  private RestTestClient client;

  @BeforeEach
  void setUp() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void healthEndpointRespondsUp() {
    client.get().uri("/actuator/health")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  /** Anonymní dotaz (T0, docs/reputace.md) — ověří GraphQL wiring i autorizaci ze SecurityConfig. */
  @Test
  void anonymousGraphQlQuerySucceeds() {
    client.post().uri("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("query", "{ searchFacets { cities } }"))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.errors").doesNotExist();
  }
}
