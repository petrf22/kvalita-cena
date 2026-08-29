package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.db.entity.ChainType;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChainCatalogService} bez DB — samotný nativní dotaz (norm_text/diakritika) neověří
 * Mockito, na to slouží {@code RetailChainSeedIntegrationTest} (Testcontainers). Tady jen ořez
 * limitu a odvození země, stejný vzor jako {@link ProductSearchServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChainCatalogServiceTest {

  private static final Long VIEWER_ID = 42L;

  @Mock
  private RetailChainRepository retailChainRepository;
  @Mock
  private AppUserRepository appUserRepository;

  private ChainCatalogService service() {
    I18nProperties i18nProperties = new I18nProperties();
    i18nProperties.setDefaultCountry("CZ");
    i18nProperties.setCountryCurrency(Map.of("CZ", "CZK", "SK", "EUR", "PL", "PLN"));
    CountryResolver countryResolver = new CountryResolver(i18nProperties, appUserRepository, null);
    return new ChainCatalogService(retailChainRepository, countryResolver);
  }

  private RetailChain chain(String name) {
    return RetailChain.builder().id(1L).name(name).slug(name.toLowerCase()).chainType(ChainType.CHAIN)
        .country("CZ").build();
  }

  @Test
  void blankQueryIsNormalizedToNull() {
    when(retailChainRepository.searchByText(isNull(), anyString(), anyInt())).thenReturn(List.of());

    service().search("   ", null, null, VIEWER_ID);

    verify(retailChainRepository).searchByText(isNull(), eq("CZ"), eq(20));
  }

  @Test
  void trimsQueryBeforeSearching() {
    when(retailChainRepository.searchByText(anyString(), anyString(), anyInt())).thenReturn(List.of());
    ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);

    service().search("  kau  ", null, null, VIEWER_ID);

    verify(retailChainRepository).searchByText(queryCaptor.capture(), eq("CZ"), eq(20));
    assertThat(queryCaptor.getValue()).isEqualTo("kau");
  }

  @Test
  void explicitCountryOverridesViewerDefault() {
    when(retailChainRepository.searchByText(isNull(), anyString(), anyInt())).thenReturn(List.of());

    service().search(null, "SK", null, VIEWER_ID);

    verify(retailChainRepository).searchByText(isNull(), eq("SK"), eq(20));
  }

  @Test
  void clampsFirstToMaxLimit() {
    when(retailChainRepository.searchByText(isNull(), anyString(), anyInt())).thenReturn(List.of());

    service().search(null, null, 500, VIEWER_ID);

    verify(retailChainRepository).searchByText(isNull(), eq("CZ"), eq(50));
  }

  @Test
  void defaultsFirstTo20WhenNull() {
    when(retailChainRepository.searchByText(isNull(), anyString(), anyInt())).thenReturn(List.of());

    service().search(null, null, null, VIEWER_ID);

    verify(retailChainRepository).searchByText(isNull(), eq("CZ"), eq(20));
  }

  @Test
  void firstBelowOneIsClampedToOne() {
    when(retailChainRepository.searchByText(isNull(), anyString(), anyInt())).thenReturn(List.of());

    service().search(null, null, 0, VIEWER_ID);

    verify(retailChainRepository).searchByText(isNull(), eq("CZ"), eq(1));
  }

  @Test
  void returnsWhatRepositoryYields() {
    when(retailChainRepository.searchByText(eq("kaufland"), eq("CZ"), eq(20)))
        .thenReturn(List.of(chain("Kaufland")));

    List<RetailChain> result = service().search("kaufland", null, null, VIEWER_ID);

    assertThat(result).extracting(RetailChain::getName).containsExactly("Kaufland");
  }
}
