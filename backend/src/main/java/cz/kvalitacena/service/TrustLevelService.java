package cz.kvalitacena.service;

import cz.kvalitacena.config.TrustProperties;
import cz.kvalitacena.db.entity.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Práh důvěry pro okamžité zveřejnění nového záznamu (docs/reputace.md, etapa-1 aproximace
 * úrovně T2: stáří účtu + počet vlastních záznamů). Nad prahem se nový obchod/zboží zveřejní
 * všem hned se štítkem "neověřeno"; pod prahem ho vidí jen autor, dokud ho nepotvrdí víc
 * přispěvatelů ({@code app.catalog.draft-confirmations}, viz
 * {@link ProductCatalogService#promoteIfConfirmed} a {@link StoreService#promoteIfConfirmed}).
 */
@Service
@RequiredArgsConstructor
public class TrustLevelService {

  private final TrustProperties trustProperties;

  public boolean isTrusted(AppUser user) {
    if (user == null || user.getCreatedAt() == null) {
      return false;
    }
    boolean oldEnough = user.getCreatedAt()
        .isBefore(OffsetDateTime.now().minusDays(trustProperties.getMinAccountAgeDays()));
    return oldEnough && user.getObservationCount() >= trustProperties.getMinObservations();
  }
}
