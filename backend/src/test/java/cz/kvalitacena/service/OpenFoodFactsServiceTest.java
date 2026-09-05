package cz.kvalitacena.service;

import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.repo.OffProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenFoodFactsServiceTest {

  private static final String RAW_EAN = "8594001234578";
  private static final String GTIN = "08594001234578";

  @Mock OffProductRepository repository;
  @Mock OpenFoodFactsApiClient apiClient;

  private OpenFoodFactsProperties properties() {
    OpenFoodFactsProperties result = new OpenFoodFactsProperties();
    result.setEnabled(true);
    result.setPositiveCacheTtl(Duration.ofDays(7));
    result.setNegativeCacheTtl(Duration.ofDays(1));
    result.setMaxRequestsPerMinute(15);
    return result;
  }

  @Test
  void freshSnapshotAvoidsApiCall() {
    OffProduct cached = OffProduct.builder().gtin(GTIN).fetchStatus(OffFetchStatus.FOUND)
        .productName("Máslo").fetchedAt(OffsetDateTime.now()).build();
    when(repository.findById(GTIN)).thenReturn(Optional.of(cached));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product()).isSameAs(cached);
    verify(apiClient, never()).fetch(any());
  }

  @Test
  void remoteProductIsNormalizedAndStored() {
    when(repository.findById(GTIN)).thenReturn(Optional.empty());
    when(apiClient.fetch(RAW_EAN)).thenReturn(Optional.of(new OffRemoteProduct(
        "cs", "Máslo", Map.of("cs", "Máslo", "de", "Butter"), List.of("cs", "de"), "Mlékárna",
        new BigDecimal("250"), "G", List.of("en:butters"),
        "https://images.openfoodfacts.org/front.jpg", null, List.of(), List.of("en:e330"), 12L,
        OffsetDateTime.now())));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product().getGtin()).isEqualTo(GTIN);
    assertThat(result.product().getProductQuantity()).isEqualByComparingTo("250");
    assertThat(result.product().getCategoryTags()).containsExactly("en:butters");
    assertThat(result.product().getAdditivesTags()).containsExactly("en:e330");
    verify(apiClient).fetch(RAW_EAN);
  }

  @Test
  void notFoundIsPersistedAsNegativeCache() {
    when(repository.findById(GTIN)).thenReturn(Optional.empty());
    when(apiClient.fetch(RAW_EAN)).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.NOT_FOUND);
    assertThat(result.product().getFetchStatus()).isEqualTo(OffFetchStatus.NOT_FOUND);
  }

  @Test
  void outageReturnsStaleFoundSnapshot() {
    OffProduct stale = OffProduct.builder().gtin(GTIN).fetchStatus(OffFetchStatus.FOUND)
        .productName("Máslo").fetchedAt(OffsetDateTime.now().minusDays(8)).build();
    when(repository.findById(GTIN)).thenReturn(Optional.of(stale));
    when(apiClient.fetch(RAW_EAN)).thenThrow(new RestClientException("offline"));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product()).isSameAs(stale);
  }

  @Test
  void disabledIntegrationFailsSoftWithoutSnapshot() {
    OpenFoodFactsProperties properties = properties();
    properties.setEnabled(false);
    when(repository.findById(GTIN)).thenReturn(Optional.empty());

    OffLookupResult result = service(properties).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.UNAVAILABLE);
    verify(apiClient, never()).fetch(any());
  }

  private OpenFoodFactsService service(OpenFoodFactsProperties properties) {
    return new OpenFoodFactsService(repository, apiClient, properties, new OffCategoryMapper());
  }

  /**
   * Rozšíření appky o jazyk musí dorazit i ke zboží, které je v katalogu dávno — snapshot
   * stažený s menší sadou jazyků se proto považuje za nečerstvý bez ohledu na TTL
   * (docs/lokalizace.md, "Rozšíření o jazyk").
   */
  @Test
  void snapshotMissingANewlyAddedLanguageIsRefetchedEvenWithinTtl() {
    OffProduct cached = OffProduct.builder().gtin(GTIN).fetchStatus(OffFetchStatus.FOUND)
        .productName("Máslo").names(new java.util.LinkedHashMap<>(Map.of("cs", "Máslo")))
        .nameLocales(new java.util.ArrayList<>(List.of("cs", "sk")))
        .fetchedAt(OffsetDateTime.now()).build();
    when(repository.findById(GTIN)).thenReturn(Optional.of(cached));
    when(apiClient.nameLocales()).thenReturn(List.of("cs", "de", "sk"));
    when(apiClient.fetch(RAW_EAN)).thenReturn(Optional.of(new OffRemoteProduct(
        "cs", "Máslo", Map.of("cs", "Máslo", "de", "Butter"), List.of("cs", "de", "sk"), null,
        null, null, List.of(), null, null, List.of(), List.of(), 1L, OffsetDateTime.now())));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    verify(apiClient).fetch(RAW_EAN);
    assertThat(result.product().getNames()).containsEntry("de", "Butter");
    assertThat(result.product().getNameLocales()).containsExactly("cs", "de", "sk");
  }

  /** Zboží, které OFF nezná, se kvůli novému jazyku dotazovat znovu nemá — není co překládat. */
  @Test
  void notFoundSnapshotIsNotRefetchedJustBecauseOfANewLanguage() {
    OffProduct cached = OffProduct.builder().gtin(GTIN).fetchStatus(OffFetchStatus.NOT_FOUND)
        .fetchedAt(OffsetDateTime.now()).build();
    when(repository.findById(GTIN)).thenReturn(Optional.of(cached));

    OffLookupResult result = service(properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.NOT_FOUND);
    verify(apiClient, never()).fetch(any());
  }
}
