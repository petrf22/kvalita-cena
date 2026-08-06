package cz.kvalitacena.service;

import cz.kvalitacena.config.TrustProperties;
import cz.kvalitacena.db.entity.AppUser;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Práh důvěry (docs/reputace.md, etapa-1 aproximace T2) — stáří účtu I počet vlastních
 * záznamů musí splňovat práh zároveň, jinak je autor "nedůvěryhodný" a jeho nové záznamy
 * vznikají jako DRAFT/PENDING (ProductCatalogService, StoreService).
 */
class TrustLevelServiceTest {

  private final TrustProperties trustProperties = new TrustProperties();
  private final TrustLevelService service = new TrustLevelService(trustProperties);

  {
    trustProperties.setMinAccountAgeDays(7);
    trustProperties.setMinObservations(5);
  }

  private AppUser userAgedDaysWithObservations(int ageDays, int observations) {
    return AppUser.builder()
        .createdAt(OffsetDateTime.now().minusDays(ageDays))
        .observationCount(observations)
        .build();
  }

  @Test
  void nullUserIsNeverTrusted() {
    assertThat(service.isTrusted(null)).isFalse();
  }

  @Test
  void userWithoutCreatedAtIsNeverTrusted() {
    assertThat(service.isTrusted(AppUser.builder().observationCount(100).build())).isFalse();
  }

  @Test
  void freshAccountWithEnoughObservationsIsNotTrusted() {
    assertThat(service.isTrusted(userAgedDaysWithObservations(1, 10))).isFalse();
  }

  @Test
  void oldAccountWithoutObservationsIsNotTrusted() {
    assertThat(service.isTrusted(userAgedDaysWithObservations(30, 0))).isFalse();
  }

  @Test
  void oldEnoughAccountWithEnoughObservationsIsTrusted() {
    assertThat(service.isTrusted(userAgedDaysWithObservations(8, 5))).isTrue();
  }

  @Test
  void accountJustUnderAgeThresholdIsNotYetTrusted() {
    assertThat(service.isTrusted(userAgedDaysWithObservations(6, 10))).isFalse();
  }
}
