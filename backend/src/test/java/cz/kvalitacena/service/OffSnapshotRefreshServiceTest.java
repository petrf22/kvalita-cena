package cz.kvalitacena.service;

import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffFetchStatus;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.repo.OffProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dohnání jazyků u zboží, které nikdo znovu neotevře (docs/lokalizace.md, „Rozšíření o jazyk").
 */
@ExtendWith(MockitoExtension.class)
class OffSnapshotRefreshServiceTest {

  @Mock private OffProductRepository repository;
  @Mock private OpenFoodFactsService offService;
  @Mock private OpenFoodFactsApiClient apiClient;

  private final OpenFoodFactsProperties properties = new OpenFoodFactsProperties();

  private OffSnapshotRefreshService service() {
    properties.setBackfillBatchSize(10);
    return new OffSnapshotRefreshService(repository, offService, apiClient, properties);
  }

  private OffProduct snapshot(String gtin, List<String> locales) {
    return OffProduct.builder().gtin(gtin).fetchStatus(OffFetchStatus.FOUND)
        .nameLocales(new ArrayList<>(locales)).build();
  }

  @Test
  void refreshesSnapshotsMissingALanguage() {
    when(apiClient.nameLocales()).thenReturn(List.of("cs", "de"));
    OffProduct stale = snapshot("08594001234578", List.of("cs"));
    when(repository.findMissingNameLocales("{cs,de}", 10)).thenReturn(List.of(stale));
    when(repository.findById("08594001234578"))
        .thenReturn(Optional.of(snapshot("08594001234578", List.of("cs", "de"))));
    when(offService.hasAllNameLocales(any())).thenReturn(true);

    service().refreshMissingLanguages();

    verify(offService).lookup("08594001234578");
  }

  /**
   * Limit dotazů sdílí s interaktivním skenem — když dojde, lookup vrátí starý snapshot beze
   * změny. Job to musí poznat a dávku ukončit, ne se probíjet frontou naprázdno.
   */
  @Test
  void stopsTheBatchWhenTheSnapshotComesBackUnchanged() {
    when(apiClient.nameLocales()).thenReturn(List.of("cs", "de"));
    OffProduct first = snapshot("08594001234578", List.of("cs"));
    OffProduct second = snapshot("08594001234579", List.of("cs"));
    when(repository.findMissingNameLocales("{cs,de}", 10)).thenReturn(List.of(first, second));
    when(repository.findById("08594001234578")).thenReturn(Optional.of(first));
    when(offService.hasAllNameLocales(first)).thenReturn(false);

    service().refreshMissingLanguages();

    verify(offService).lookup("08594001234578");
    verify(offService, never()).lookup(eq("08594001234579"));
  }

  @Test
  void disabledIntegrationDoesNothing() {
    properties.setEnabled(false);

    service().refreshMissingLanguages();

    verify(repository, never()).findMissingNameLocales(any(), org.mockito.ArgumentMatchers.anyInt());
  }
}
