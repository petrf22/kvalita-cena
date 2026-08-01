package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.PriceCurrentRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.RecomputeQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Přepočet agg.price_current váženým mediánem — viz docs/reputace.md ("Agregace váženým
 * mediánem, ne průměrem") a docs/datovy-model.md ("Agregace jsou tabulky, ne materialized
 * view"). Fronta existuje právě proto, aby šel přepočítat jen konkrétní buňky (produkt,
 * obchod, druh ceny), ne celou tabulku — do fronty se zapisuje synchronně při zápisu
 * observace, samotný přepočet běží asynchronně přes {@link #processQueue()}.
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
        .findByProductIdAndStoreIdAndStatus(productId, storeId, ObservationStatus.ACTIVE);

    Map<PriceKind, List<PriceObservation>> byKind = observations.stream()
        .filter(o -> o.getUnitPrice() != null)
        .collect(Collectors.groupingBy(PriceObservation::getPriceKind));

    for (Map.Entry<PriceKind, List<PriceObservation>> entry : byKind.entrySet()) {
      upsertPriceCurrent(productId, storeId, entry.getKey(), entry.getValue());
    }
  }

  private void upsertPriceCurrent(Long productId, Long storeId, PriceKind priceKind,
      List<PriceObservation> observations) {
    record WeightedValue(BigDecimal unitPrice, BigDecimal priceAmount, BigDecimal weight) {
    }

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

    Confidence confidence = nEff.compareTo(BigDecimal.valueOf(5)) >= 0 ? Confidence.HIGH
        : nEff.compareTo(BigDecimal.valueOf(2)) >= 0 ? Confidence.MEDIUM
        : Confidence.LOW;

    OffsetDateTime lastObservedAt = observations.stream()
        .map(PriceObservation::getObservedAt)
        .max(Comparator.naturalOrder())
        .orElse(null);

    PriceCurrentId id = new PriceCurrentId(productId, storeId, priceKind);
    PriceCurrent priceCurrent = priceCurrentRepository.findById(id).orElseGet(() ->
        PriceCurrent.builder().productId(productId).storeId(storeId).priceKind(priceKind).build());

    priceCurrent.setUnitPrice(weightedMedian);
    priceCurrent.setPriceAmount(medianAmount);
    priceCurrent.setNObs(values.size());
    priceCurrent.setNEff(nEff);
    priceCurrent.setSumWeight(totalWeight);
    priceCurrent.setLastObservedAt(lastObservedAt);
    priceCurrent.setConfidence(confidence);

    priceCurrentRepository.save(priceCurrent);
  }

  private BigDecimal weightFor(PriceObservation observation) {
    return observation.getSubmitterKind() == SubmitterKind.REGISTERED ? WEIGHT_REGISTERED : WEIGHT_ANONYMOUS;
  }
}
