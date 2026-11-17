package cz.kvalitacena;

import cz.kvalitacena.db.entity.ApiToken;
import cz.kvalitacena.db.entity.ApiTokenScope;
import cz.kvalitacena.db.repo.ApiTokenRepository;
import cz.kvalitacena.security.ApiTokenAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

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

  @Autowired
  private ApiTokenRepository apiTokenRepository;

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

  /**
   * {@code clientSubmissionId: ID} se z JSONu naváže na {@code UUID} v recordu — kdyby vazba
   * selhala, skončil by request chybou vazby argumentu dřív, než služba hledá zboží.
   */
  @Test
  void clientSubmissionIdBindsToUuid() {
    String mutation = "mutation($input: SubmitObservationsInput!) { submitObservations(input: $input) { id } }";
    Map<String, Object> input = Map.of("productId", "999999", "storeId", "999999",
        "clientSubmissionId", "6f1c2b7e-3a4d-4e5f-9a0b-1c2d3e4f5a6b",
        "prices", java.util.List.of(Map.of("priceKind", "REGULAR", "priceAmount", "29.90")));

    client.post().uri("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("query", mutation, "variables", Map.of("input", input)))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.errors[0].extensions.code").isEqualTo("PRODUCT_NOT_FOUND");
  }

  /** Dotaz hlubší než app.graphql.max-query-depth skončí chybou a nic nespustí (GraphQlConfig). */
  @Test
  void tooDeepGraphQlQueryIsRejected() {
    String deep = "{ __schema { types { fields { type "
        + "{ ofType ".repeat(14) + "{ name }" + " }".repeat(14) + " } } } }";

    client.post().uri("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("query", deep))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.errors").isNotEmpty()
        .jsonPath("$.data").doesNotExist();
  }

  /** Standardní introspekční dotaz GraphiQL (hloubka 13) se pod strop musí vejít — jinak by ve vývoji nešel GraphiQL. */
  @Test
  void introspectionDepthFitsUnderLimit() {
    String introspection = "{ __schema { types { fields { args { type "
        + "{ ofType ".repeat(7) + "{ name }" + " }".repeat(7) + " } } } } }";

    client.post().uri("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("query", introspection))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.errors").doesNotExist();
  }

  /**
   * Chyba vykreslená přes {@code sendError} jde ERROR dispatchem na {@code /error}, kde už
   * auth filtry neběží — bez {@code dispatcherTypeMatchers(ERROR).permitAll()} ze
   * SecurityConfig by se každá 404/400/500 navenek tvářila jako 403 bez těla.
   */
  @Test
  void unknownPathReturnsRealStatusInsteadOfForbidden() {
    client.get().uri("/api/me/neexistuje")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.status").isEqualTo(404);
  }

  @Test
  void workerSeesRealErrorStatus() {
    String token = ApiTokenAuthenticationFilter.TOKEN_PREFIX + UUID.randomUUID();
    apiTokenRepository.save(ApiToken.builder()
        .name("smoke-test")
        .tokenHash(ApiTokenAuthenticationFilter.sha256(token))
        .scope(ApiTokenScope.RECEIPT_WORKER)
        .build());

    client.get().uri("/api/worker/receipts/neexistuje/x")
        .header("Authorization", "Bearer " + token)
        .exchange()
        .expectStatus().isNotFound();

    client.post().uri("/api/worker/receipts/1/result")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{rozbitý json")
        .exchange()
        .expectStatus().isBadRequest();
  }

  /** Povolený ERROR dispatch nesmí otevřít samotné URL — worker endpoint dál jen se strojovým tokenem. */
  @Test
  void anonymousStillForbiddenOnWorkerEndpoint() {
    client.post().uri("/api/worker/receipts/lease")
        .exchange()
        .expectStatus().isForbidden();
  }
}
