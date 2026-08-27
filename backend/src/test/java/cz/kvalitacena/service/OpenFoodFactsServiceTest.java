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

    OffLookupResult result = new OpenFoodFactsService(repository, apiClient, properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product()).isSameAs(cached);
    verify(apiClient, never()).fetch(any());
  }

  @Test
  void remoteProductIsNormalizedAndStored() {
    when(repository.findById(GTIN)).thenReturn(Optional.empty());
    when(apiClient.fetch(RAW_EAN)).thenReturn(Optional.of(new OffRemoteProduct(
        "Máslo", "Mlékárna", new BigDecimal("250"), "G", List.of("en:butters"),
        "https://images.openfoodfacts.org/front.jpg", null, 12L, OffsetDateTime.now())));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OffLookupResult result = new OpenFoodFactsService(repository, apiClient, properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product().getGtin()).isEqualTo(GTIN);
    assertThat(result.product().getProductQuantity()).isEqualByComparingTo("250");
    assertThat(result.product().getCategoryTags()).containsExactly("en:butters");
    verify(apiClient).fetch(RAW_EAN);
  }

  @Test
  void notFoundIsPersistedAsNegativeCache() {
    when(repository.findById(GTIN)).thenReturn(Optional.empty());
    when(apiClient.fetch(RAW_EAN)).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OffLookupResult result = new OpenFoodFactsService(repository, apiClient, properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.NOT_FOUND);
    assertThat(result.product().getFetchStatus()).isEqualTo(OffFetchStatus.NOT_FOUND);
  }

  @Test
  void outageReturnsStaleFoundSnapshot() {
    OffProduct stale = OffProduct.builder().gtin(GTIN).fetchStatus(OffFetchStatus.FOUND)
        .productName("Máslo").fetchedAt(OffsetDateTime.now().minusDays(8)).build();
    when(repository.findById(GTIN)).thenReturn(Optional.of(stale));
    when(apiClient.fetch(RAW_EAN)).thenThrow(new RestClientException("offline"));

    OffLookupResult result = new OpenFoodFactsService(repository, apiClient, properties()).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.FOUND);
    assertThat(result.product()).isSameAs(stale);
  }

  @Test
  void disabledIntegrationFailsSoftWithoutSnapshot() {
    OpenFoodFactsProperties properties = properties();
    properties.setEnabled(false);
    when(repository.findById(GTIN)).thenReturn(Optional.empty());

    OffLookupResult result = new OpenFoodFactsService(repository, apiClient, properties).lookup(RAW_EAN);

    assertThat(result.status()).isEqualTo(OffLookupStatus.UNAVAILABLE);
    verify(apiClient, never()).fetch(any());
  }
}
