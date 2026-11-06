package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jednotky cen při přesunu observací na jiné zboží — dopárování zboží z účtenky
 * (docs/reputace.md, „Zboží z účtenky") i moderátorské sloučení duplicit ({@code mergeProducts}).
 *
 * <p>Observace nese snapshot {@code net_content_base} a z něj generovanou {@code unit_price}
 * (docs/datovy-model.md). Přesun observace na jiné zboží mění jen {@code product_id}, takže
 * cena za kelímek ze zboží bez gramáže by se u 150g jogurtu agregovala jako cena za
 * kg — 19,90 Kč místo 132,67 Kč/kg. Proto se před přesunem porovná, CO jednotková cena znamená:
 *
 * <ul>
 *   <li>stejný význam (za kg, za l, za kus, za balení neznámé velikosti) — přesun beze změny;</li>
 *   <li>cena za balení neznámé velikosti na zboží s pevnou gramáží — dopárování i sloučení je
 *       výslovné tvrzení „to balení bylo tohle zboží", takže observace převezme gramáž cíle
 *       (u MULTIBUY krát počet kusů, stejně jako {@code PriceObservationService});</li>
 *   <li>cokoli jiného (cena za kus na váhové zboží, cena za kg na zboží bez gramáže…) se
 *       odmítne s chybou, kterou zvolí volající ({@code RECEIPT_ITEM_REMATCH_UNIT_MISMATCH},
 *       {@code MODERATION_PRODUCT_MERGE_UNIT_MISMATCH}) — převést to nejde.</li>
 * </ul>
 *
 * <p>Observace jednotku nenese, takže význam ceny za balení se skládá ze snapshotu a ze zboží,
 * jak ho viděl AUTOR ceny: {@code PriceObservationService} bere gramáž z jeho osobního patche
 * ({@link ProductOverlayService#applyOverlay}), ne z {@code core.product}. Snapshot jiný než
 * „balení neznámé velikosti" ({@code net_content_base = 1}, u MULTIBUY počet kusů) je známá
 * gramáž a nikdy se nepřepisuje — jinak by 0,5 kg z patche po sloučení do 1kg cíle zlevnilo
 * historickou cenu za kg na polovinu. Snapshot 1 je neznámé balení jen tehdy, když autor ani dnes
 * nevidí žádnou gramáž; jinak je to 1 kg / 1 l / 1 ks. Cíl se čte tak, jak ho vidí agregát
 * (veřejná vrstva včetně Open Food Facts) — zboží z OFF má v {@code core.product} gramáž NULL.
 */
@Component
@RequiredArgsConstructor
class ProductMergeUnits {

  /** Co znamená jednotková cena: za kg, za litr, za kus, nebo za balení neznámé velikosti. */
  enum PriceUnit { MASS, VOLUME, COUNT, PACKAGE }

  /**
   * Výsledek posouzení: autoři ({@code null} = anonymní zápisy), jejichž ceny za balení neznámé
   * velikosti musí při přesunu převzít gramáž cíle ({@link #adoptTargetNetContent}).
   */
  record Compatibility(Product target, Set<Long> adoptingSubmitters) {
    boolean adoptsFor(Long submitterId) {
      return adoptingSubmitters.contains(submitterId);
    }
  }

  private final JdbcTemplate jdbcTemplate;
  private final ProductOverlayService productOverlayService;

  static PriceUnit unitOf(Product product) {
    if (product.isVariableWeight()) {
      return product.getUnitBase() == UnitBase.VOLUME ? PriceUnit.VOLUME : PriceUnit.MASS;
    }
    if (product.getNetContentValue() == null) {
      return PriceUnit.PACKAGE;
    }
    return knownUnit(product);
  }

  /** Veličina zboží s gramáží (nebo se snapshotem známé gramáže, ať dnes vypadá jakkoli). */
  private static PriceUnit knownUnit(Product product) {
    if (product.getUnitBase() == UnitBase.MASS) return PriceUnit.MASS;
    if (product.getUnitBase() == UnitBase.VOLUME) return PriceUnit.VOLUME;
    return PriceUnit.COUNT;
  }

  /**
   * Ověří, že ceny zdroje jde přesunout na cíl, jinak vyhodí {@code mismatch}. Posuzuje všechny
   * ceny zdroje (i když se pak stěhují jen ceny jednoho autora) — zboží bez cen projde vždy.
   */
  Compatibility requireCompatible(Product source, Product target, ErrorCode mismatch) {
    Product effectiveTarget = productOverlayService.applyOverlay(target, null);
    PriceUnit targetUnit = unitOf(effectiveTarget);
    Map<Long, Product> bySubmitter = new HashMap<>();
    Set<Long> adopting = new HashSet<>();
    List<Map<String, Object>> groups = jdbcTemplate.queryForList(
        "SELECT DISTINCT submitter_id, quantity_basis, net_content_base = " + packageBaseSql()
            + " AS unknown_package FROM core.price_observation WHERE product_id = ?", source.getId());
    for (Map<String, Object> group : groups) {
      Long submitterId = group.get("submitter_id") == null ? null : ((Number) group.get("submitter_id")).longValue();
      PriceUnit unit = switch ((String) group.get("quantity_basis")) {
        case "PER_KG" -> PriceUnit.MASS;
        case "PER_L" -> PriceUnit.VOLUME;
        default -> {
          Product seen = bySubmitter.computeIfAbsent(submitterId,
              id -> productOverlayService.applyOverlay(source, id));
          yield Boolean.TRUE.equals(group.get("unknown_package")) ? unitOf(seen) : knownUnit(seen);
        }
      };
      if (unit == targetUnit) {
        continue;
      }
      if (unit == PriceUnit.PACKAGE && !effectiveTarget.isVariableWeight() && targetUnit != PriceUnit.PACKAGE) {
        adopting.add(submitterId);
        continue;
      }
      throw new ValidationException(mismatch);
    }
    return new Compatibility(effectiveTarget, adopting);
  }

  /**
   * Ceny za balení neznámé velikosti ({@code PACKAGE}/{@code PER_PIECE} se snapshotem
   * {@link #packageBaseSql()}) převezmou gramáž cíle — jen autorů, které posouzení označilo
   * a které volající jmenuje ({@code submitters}). Známé snapshoty zůstanou, jak jsou.
   *
   * @return ID observací, kterým se snapshot přepsal (změna hlasu je podle nich vrátí)
   */
  List<Long> adoptTargetNetContent(Long sourceId, Compatibility compatibility, Collection<Long> submitters) {
    BigDecimal base = compatibility.target().getNetContentBase();
    List<Long> adopted = new ArrayList<>();
    for (Long submitterId : submitters) {
      if (!compatibility.adoptsFor(submitterId)) continue;
      adopted.addAll(jdbcTemplate.queryForList(
          "UPDATE core.price_observation SET net_content_base = ? * " + packageBaseSql()
              + " WHERE product_id = ? AND quantity_basis IN ('PACKAGE', 'PER_PIECE')"
              + " AND net_content_base = " + packageBaseSql()
              + " AND submitter_id IS NOT DISTINCT FROM ? RETURNING id",
          Long.class, base, sourceId, submitterId));
    }
    return adopted;
  }

  /**
   * Snapshot ceny za balení neznámé velikosti — {@code net_content_base = 1} (u MULTIBUY počet
   * kusů), stejně jako ho zapíše {@code PriceObservationService} pro zboží bez gramáže.
   */
  static String packageBaseSql() {
    return "CASE WHEN price_kind = 'MULTIBUY' THEN COALESCE(multibuy_qty, 1) ELSE 1 END";
  }
}
