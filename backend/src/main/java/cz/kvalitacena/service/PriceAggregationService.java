package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.PriceDailyRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.RecomputeQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Přepočet agg.price_current (a agg.price_daily, viz {@link #recomputeDaily}) váženým mediánem —
 * viz docs/reputace.md ("Agregace váženým mediánem, ne průměrem") a docs/datovy-model.md
 * ("Agregace jsou tabulky, ne materialized view"). Fronta existuje právě proto, aby šel
 * přepočítat jen konkrétní buňky (produkt, obchod), ne celou tabulku — do fronty se zapisuje
 * synchronně při zápisu observace, samotný přepočet běží asynchronně přes {@link #processQueue()}.
 *
 * <p>Etapa 1 (MVP): váha záznamu je jen složka {@code L} z docs/reputace.md — anonym 0,15,
 * registrovaný 1,00. Plný vzorec (přesnost, zkušenost, stáří účtu, penalizace, skupiny
 * důvěry) přijde s reputačním systémem v etapě 2/3, až budou existovat data, ze kterých by
 * šel počítat (potvrzení, historie souhlasu).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAggregationService {

  private static final BigDecimal WEIGHT_ANONYMOUS = new BigDecimal("0.15");
  private static final BigDecimal WEIGHT_REGISTERED = BigDecimal.ONE;
  private static final MathContext MC = MathContext.DECIMAL64;

  private final RecomputeQueueRepository recomputeQueueRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final PriceCurrentRepository priceCurrentRepository;
  private final PriceDailyRepository priceDailyRepository;
  private final ProductRepository productRepository;

  @Transactional
  public void enqueueRecompute(Long productId, Long storeId, RecomputeReason reason) {
    recomputeQueueRepository.save(RecomputeQueue.builder()
        .productId(productId)
        .storeId(storeId)
        .reason(reason)
        .build());
  }

  /** Drénuje frontu po dávkách — viz docs/datovy-model.md, "Agregace jsou tabulky, ne materialized view". */
  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void processQueue() {
    List<RecomputeQueue> pending = recomputeQueueRepository.findTop200ByProcessedAtIsNullOrderByEnqueuedAtAsc();
    if (pending.isEmpty()) return;

    // Víc událostí pro stejnou buňku (produkt, obchod) přepočítáme jen jednou za dávku.
    record Cell(Long productId, Long storeId) {
    }
    Set<Cell> cells = pending.stream().map(q -> new Cell(q.getProductId(), q.getStoreId())).collect(Collectors.toSet());

    for (Cell cell : cells) {
      recomputeCell(cell.productId(), cell.storeId());
    }

    OffsetDateTime now = OffsetDateTime.now();
    pending.forEach(q -> q.setProcessedAt(now));
    recomputeQueueRepository.saveAll(pending);

    log.info("Přepočet cen: {} buněk (produkt, obchod) z {} položek fronty.", cells.size(), pending.size());
  }

  private void recomputeCell(Long productId, Long storeId) {
    List<PriceObservation> observations = priceObservationRepository
        .findByProductIdAndStoreIdAndStatus(productId, storeId, ObservationStatus.ACTIVE).stream()
        .filter(o -> o.getUnitPrice() != null)
        .toList();

    // Bezkódová druhová položka (Product.isGeneric) nemá jednoznačný identifikátor jako EAN,
    // takže i shoda víc lidí na stejné ceně je slabší důkaz — confidence buňky se pro ni
    // zastropuje na MEDIUM (docs/reputace.md, "Zboží bez čárového kódu"). Váhu jednotlivého
    // záznamu (weightFor) to záměrně NEMĚNÍ: je konstantní násobek napříč celou buňkou, takže
    // by na vážený medián i Kishovo n_eff nemělo žádný vliv.
    boolean generic = productRepository.findById(productId).map(Product::isGeneric).orElse(false);

    recomputeCurrent(productId, storeId, observations, generic);
    recomputeDaily(productId, storeId, observations);
  }

  /** Druh ceny + měna dohromady — akční/klubová/běžná cena se nikdy nemíchá (viz docs/datovy-model.md)
   *  a stejně tak se nesmí míchat ani měna (docs/lokalizace.md, příhraniční prodejna CZK/EUR). */
  private record KindCurrency(PriceKind priceKind, String currency) {
  }

  private void recomputeCurrent(Long productId, Long storeId, List<PriceObservation> observations, boolean generic) {
    Map<KindCurrency, List<PriceObservation>> byKindCurrency = observations.stream()
        .collect(Collectors.groupingBy(o -> new KindCurrency(o.getPriceKind(), o.getCurrency())));

    for (Map.Entry<KindCurrency, List<PriceObservation>> entry : byKindCurrency.entrySet()) {
      upsertPriceCurrent(productId, storeId, entry.getKey(), entry.getValue(), generic);
    }

    // Osiřelé řádky: dvojice (druh ceny, měna), která dřív měla agregát, ale teď už nemá
    // žádnou ACTIVE observaci (poslední záznam byl zamítnut/smazán) — jinak by UI dál
    // ukazovalo neexistující cenu. Porovnává se PÁR, ne jen priceKind — jinak by se při
    // osiření CZK řádku smazal i platný EUR řádek stejného druhu ceny.
    List<PriceCurrent> orphaned = priceCurrentRepository.findByProductId(productId).stream()
        .filter(pc -> Objects.equals(pc.getStoreId(), storeId)
            && !byKindCurrency.containsKey(new KindCurrency(pc.getPriceKind(), pc.getCurrency())))
        .toList();
    if (!orphaned.isEmpty()) {
      priceCurrentRepository.deleteAll(orphaned);
    }
  }

  private void upsertPriceCurrent(Long productId, Long storeId, KindCurrency kindCurrency,
      List<PriceObservation> observations, boolean generic) {
    WeightedStats stats = computeWeighted(observations);

    OffsetDateTime lastObservedAt = observations.stream()
        .map(PriceObservation::getObservedAt)
        .max(Comparator.naturalOrder())
        .orElse(null);

    // MAX napříč observacemi buňky, ne medián — radši akci zobrazit o něco déle než skrýt
    // ještě platnou (docs/datovy-model.md). Vypršelou akci teprve filtruje čtecí vrstva
    // (ProductGraphQlController), tahle hodnota jen říká, kdy poslední z nich přestane platit.
    LocalDate promoValidTo = observations.stream()
        .map(PriceObservation::getPromoValidTo)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);

    PriceCurrentId id = new PriceCurrentId(productId, storeId, kindCurrency.priceKind(), kindCurrency.currency());
    PriceCurrent priceCurrent = priceCurrentRepository.findById(id).orElseGet(() ->
        PriceCurrent.builder().productId(productId).storeId(storeId)
            .priceKind(kindCurrency.priceKind()).currency(kindCurrency.currency()).build());

    priceCurrent.setUnitPrice(stats.unitPrice());
    priceCurrent.setPriceAmount(stats.priceAmount());
    priceCurrent.setNObs(stats.count());
    priceCurrent.setNEff(stats.nEff());
    priceCurrent.setSumWeight(stats.sumWeight());
    priceCurrent.setLastObservedAt(lastObservedAt);
    priceCurrent.setConfidence(confidenceFor(stats.nEff(), generic));
    priceCurrent.setPromoValidTo(promoValidTo);

    priceCurrentRepository.save(priceCurrent);
  }

  /**
   * Denní řada pro graf (agg.price_daily) — používá TÝŽ {@link #computeWeighted}, tedy tytéž
   * váhy (0,15 / 1,00) jako aktuální cena. f_recency z docs/reputace.md se na historii záměrně
   * NEAPLIKUJE ("jen pro aktuální cenu, ne pro historii v grafu"), jinak by starší body v grafu
   * klesaly bez důvodu.
   *
   * <p>Smaže a vloží znovu — záměr, ne lenost: když se observace zamítne (moderace), musí
   * odpovídající denní řádek zmizet, upsert by nechal duchy. Cena je přepočet celé historie
   * jedné buňky při každém novém hlášení; při objemech etapy 1 irelevantní (viz plán, riziko
   * "agg.recompute_queue nemá day").
   */
  private void recomputeDaily(Long productId, Long storeId, List<PriceObservation> observations) {
    priceDailyRepository.deleteByProductIdAndStoreId(productId, storeId);

    record DayKey(PriceKind priceKind, String currency, LocalDate day) {
    }

    Map<DayKey, List<PriceObservation>> byDay = observations.stream()
        .collect(Collectors.groupingBy(o -> new DayKey(o.getPriceKind(), o.getCurrency(), utcDay(o.getObservedAt()))));

    byDay.forEach((key, group) -> {
      WeightedStats stats = computeWeighted(group);
      priceDailyRepository.save(PriceDaily.builder()
          .productId(productId)
          .storeId(storeId)
          .priceKind(key.priceKind())
          .currency(key.currency())
          .day(key.day())
          .unitPrice(stats.unitPrice())
          .priceAmount(stats.priceAmount())
          .nObs(stats.count())
          .nEff(stats.nEff())
          .sumWeight(stats.sumWeight())
          .build());
    });
  }

  /** core.day_utc(observed_at) v Javě — musí znamenat totéž jako stejnojmenná funkce v DB (viz Liquibase). */
  private LocalDate utcDay(OffsetDateTime observedAt) {
    return observedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  private record WeightedValue(BigDecimal unitPrice, BigDecimal priceAmount, BigDecimal weight) {
  }

  private record WeightedStats(BigDecimal unitPrice, BigDecimal priceAmount, BigDecimal sumWeight,
                                BigDecimal nEff, int count) {
  }

  /**
   * Vážený medián a Kishova efektivní velikost vzorku — na jednom místě, aby se za čas
   * nerozešel denní ({@link #recomputeDaily}) a aktuální ({@link #upsertPriceCurrent}) medián.
   */
  private WeightedStats computeWeighted(List<PriceObservation> observations) {
    List<WeightedValue> values = observations.stream()
        .map(o -> new WeightedValue(o.getUnitPrice(), o.getPriceAmount(), weightFor(o)))
        .sorted(Comparator.comparing(WeightedValue::unitPrice))
        .toList();

    BigDecimal totalWeight = values.stream().map(WeightedValue::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sumSquares = values.stream()
        .map(v -> v.weight().multiply(v.weight()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    // Kishova efektivní velikost vzorku (Σw)² / Σw² — viz docs/reputace.md.
    BigDecimal nEff = sumSquares.signum() == 0
        ? BigDecimal.ZERO
        : totalWeight.multiply(totalWeight).divide(sumSquares, MC);

    BigDecimal half = totalWeight.divide(BigDecimal.valueOf(2), MC);
    BigDecimal cumulative = BigDecimal.ZERO;
    BigDecimal weightedMedian = values.get(values.size() - 1).unitPrice();
    BigDecimal medianAmount = values.get(values.size() - 1).priceAmount();
    for (int i = 0; i < values.size(); i++) {
      cumulative = cumulative.add(values.get(i).weight());
      if (cumulative.compareTo(half) >= 0) {
        if (cumulative.compareTo(half) == 0 && i + 1 < values.size()) {
          // Přesná rovnost na hranici — lineárně interpolujeme mezi p_k a p_{k+1}.
          weightedMedian = values.get(i).unitPrice().add(values.get(i + 1).unitPrice())
              .divide(BigDecimal.valueOf(2), MC);
          medianAmount = values.get(i).priceAmount().add(values.get(i + 1).priceAmount())
              .divide(BigDecimal.valueOf(2), MC);
        } else {
          weightedMedian = values.get(i).unitPrice();
          medianAmount = values.get(i).priceAmount();
        }
        break;
      }
    }

    return new WeightedStats(weightedMedian, medianAmount, totalWeight, nEff, values.size());
  }

  private Confidence confidenceFor(BigDecimal nEff, boolean generic) {
    Confidence raw = nEff.compareTo(BigDecimal.valueOf(5)) >= 0 ? Confidence.HIGH
        : nEff.compareTo(BigDecimal.valueOf(2)) >= 0 ? Confidence.MEDIUM
        : Confidence.LOW;
    // Bezkódová položka nemá jednoznačný identifikátor jako EAN — i shoda víc lidí na stejné
    // ceně je slabší důkaz, proto strop MEDIUM bez ohledu na n_eff (docs/reputace.md).
    return generic && raw == Confidence.HIGH ? Confidence.MEDIUM : raw;
  }

  private BigDecimal weightFor(PriceObservation observation) {
    return observation.getSubmitterKind() == SubmitterKind.REGISTERED ? WEIGHT_REGISTERED : WEIGHT_ANONYMOUS;
  }
}
