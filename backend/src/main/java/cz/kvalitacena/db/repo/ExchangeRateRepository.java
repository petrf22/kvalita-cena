package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ExchangeRate;
import cz.kvalitacena.db.entity.ExchangeRateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, ExchangeRateId> {

  /**
   * Kurz platný k danému dni — ČNB nepublikuje o víkendech a svátcích, takže "platný k datu"
   * NIKDY nesmí být rovnost na rate_date (FxRateService). Poslední předchozí publikovaný lístek
   * platí dál až do dalšího vydání.
   */
  Optional<ExchangeRate> findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate at);

  /** Nejnovější stažený den napříč měnami — odkud navazuje catch-up (ExchangeRateSyncService) a FxInfo.latestRateDate. */
  Optional<ExchangeRate> findTopByOrderByRateDateDesc();

  /** Nejstarší stažený den pro danou měnu — kolik historie máme reálně k dispozici. */
  Optional<ExchangeRate> findTopByCurrencyOrderByRateDateAsc(String currency);
}
