package cz.kvalitacena.service;

import cz.kvalitacena.config.PrivacyProperties;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductAliasConfirmationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * docs/soukromi.md, "Retence vazby observace → uživatel: 180 dní" — bez tohohle jobu appka
 * poruší vlastní slib, že "moje příspěvky" ukazují jen posledních 180 dní.
 */
@ExtendWith(MockitoExtension.class)
class PseudonymizationServiceTest {

  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private ProductAliasConfirmationRepository aliasConfirmationRepository;

  private final PrivacyProperties privacyProperties = new PrivacyProperties();

  {
    privacyProperties.setPseudonymizationDays(180);
  }

  @Test
  void pseudonymizesObservationsOlderThanConfiguredWindow() {
    when(priceObservationRepository.pseudonymizeObservationsBefore(any())).thenReturn(3);
    PseudonymizationService service = new PseudonymizationService(
        priceObservationRepository, aliasConfirmationRepository, privacyProperties);

    service.pseudonymizeOldObservations();

    ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(priceObservationRepository).pseudonymizeObservationsBefore(cutoffCaptor.capture());
    verify(aliasConfirmationRepository).pseudonymizeBefore(any());
    verifyNoMoreInteractions(priceObservationRepository);
    assertThat(cutoffCaptor.getValue())
        .isCloseTo(OffsetDateTime.now().minusDays(180), within(5, ChronoUnit.SECONDS));
  }

  @Test
  void respectsConfiguredWindowLength() {
    privacyProperties.setPseudonymizationDays(30);
    when(priceObservationRepository.pseudonymizeObservationsBefore(any())).thenReturn(0);
    PseudonymizationService service = new PseudonymizationService(
        priceObservationRepository, aliasConfirmationRepository, privacyProperties);

    service.pseudonymizeOldObservations();

    ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(priceObservationRepository).pseudonymizeObservationsBefore(cutoffCaptor.capture());
    verify(aliasConfirmationRepository).pseudonymizeBefore(any());
    assertThat(cutoffCaptor.getValue())
        .isCloseTo(OffsetDateTime.now().minusDays(30), within(5, ChronoUnit.SECONDS));
  }
}
