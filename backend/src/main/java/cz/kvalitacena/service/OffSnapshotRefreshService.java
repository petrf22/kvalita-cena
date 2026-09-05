package cz.kvalitacena.service;

import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.repo.OffProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dotažení jazyků do snapshotů, které vznikly dřív, než appka uměla dnešní sadu jazyků
 * (docs/lokalizace.md, „Rozšíření o jazyk"). Bez něj by se nový jazyk objevil jen u zboží,
 * které si někdo znovu otevře — {@link OpenFoodFactsService} snapshot s neúplným
 * {@code name_locales} sice považuje za nečerstvý, ale samo od sebe ho nikdo nepřečte.
 *
 * <p>Stejný vzorec jako {@link cz.kvalitacena.service.fx.ExchangeRateSyncService}:
 * {@code @EnableScheduling} je už v {@code SchedulingConfig}, žádné ShedLock v projektu není
 * a zápis je idempotentní, takže souběh dvou instancí nic nezkazí.
 *
 * <p>Job NEMÁ vlastní limit dotazů — jde přes {@link OpenFoodFactsService#lookup}, a tím přes
 * týž {@code max-requests-per-minute} jako interaktivní sken. Když limit dojde, lookup vrátí
 * starý snapshot beze změny; job to pozná (jazyky pořád chybí) a dávku ukončí, místo aby se
 * probíjel frontou naprázdno — přednost má vždy uživatel u kasy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OffSnapshotRefreshService {

  private final OffProductRepository repository;
  private final OpenFoodFactsService offService;
  private final OpenFoodFactsApiClient apiClient;
  private final OpenFoodFactsProperties properties;

  @Scheduled(cron = "${app.external.open-food-facts.backfill-cron}")
  public void refreshMissingLanguages() {
    if (!properties.isEnabled()) return;
    List<String> locales = apiClient.nameLocales();
    if (locales.isEmpty()) return;

    List<OffProduct> stale = repository.findMissingNameLocales(
        arrayLiteral(locales), properties.getBackfillBatchSize());
    if (stale.isEmpty()) return;

    int refreshed = 0;
    for (OffProduct product : stale) {
      offService.lookup(product.getGtin());
      OffProduct updated = repository.findById(product.getGtin()).orElse(null);
      if (updated == null || !offService.hasAllNameLocales(updated)) break;
      refreshed++;
    }
    log.info("Snapshoty OFF: doplněny jazyky {} u {} z {} zbývajících záznamů.",
        locales, refreshed, stale.size());
  }

  /** {cs,de,en} — jazyky jsou z konfigurace a projdou přes {@code [a-z]{2}}, ne uživatelský vstup. */
  private String arrayLiteral(List<String> locales) {
    return "{" + String.join(",", locales) + "}";
  }
}
