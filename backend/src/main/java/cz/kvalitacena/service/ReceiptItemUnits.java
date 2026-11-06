package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Jednotky cen při dopárování zboží z účtenky (docs/reputace.md, „Zboží z účtenky").
 *
 * <p>Observace nese snapshot {@code net_content_base} a z něj generovanou {@code unit_price}
 * (docs/datovy-model.md). Přesun observace na jiné zboží mění jen {@code product_id}, takže
 * cena za kelímek ze zboží z účtenky bez gramáže by se u 150g jogurtu agregovala jako cena za
 * kg — 19,90 Kč místo 132,67 Kč/kg. Proto se před přesunem porovná, CO jednotková cena znamená:
 *
 * <ul>
 *   <li>stejný význam (za kg, za l, za kus, za balení neznámé velikosti) — přesun beze změny;</li>
 *   <li>cena za balení neznámé velikosti na zboží s pevnou gramáží — dopárování je výslovné
 *       tvrzení „to balení bylo tohle zboží", takže observace převezme gramáž cíle (u MULTIBUY
 *       krát počet kusů, stejně jako {@code PriceObservationService});</li>
 *   <li>cokoli jiného (cena za kus na váhové zboží, cena za kg na zboží bez gramáže…) se
 *       odmítne s {@code RECEIPT_ITEM_REMATCH_UNIT_MISMATCH} — převést to nejde.</li>
 * </ul>
 *
 * Význam ceny za balení se čte ze zdroje, ne z observace: zboží z účtenky dostane gramáž jen při
 * založení z tisku a observace bez ní mají {@code net_content_base = 1}.
 */
@Component
@RequiredArgsConstructor
class ReceiptItemUnits {

  /** Co znamená jednotková cena: za kg, za litr, za kus, nebo za balení neznámé velikosti. */
  enum PriceUnit { MASS, VOLUME, COUNT, PACKAGE }

  private final JdbcTemplate jdbcTemplate;

  static PriceUnit unitOf(Product product) {
    if (product.isVariableWeight()) {
      return product.getUnitBase() == UnitBase.VOLUME ? PriceUnit.VOLUME : PriceUnit.MASS;
    }
    if (product.getNetContentValue() == null) {
      return PriceUnit.PACKAGE;
    }
    return switch (product.getUnitBase()) {
      case MASS -> PriceUnit.MASS;
      case VOLUME -> PriceUnit.VOLUME;
      case COUNT -> PriceUnit.COUNT;
    };
  }

  /**
   * Ověří, že ceny zboží z účtenky jde přesunout na cíl, jinak {@code RECEIPT_ITEM_REMATCH_UNIT_MISMATCH}.
   *
   * @return {@code true}, když ceny za balení musí převzít gramáž cíle ({@link #adoptTargetNetContent})
   */
  boolean requireCompatible(Product source, Product target) {
    List<String> bases = jdbcTemplate.queryForList(
        "SELECT DISTINCT quantity_basis FROM core.price_observation WHERE product_id = ?", String.class, source.getId());
    PriceUnit targetUnit = unitOf(target);
    boolean adopt = false;
    for (String basis : bases) {
      PriceUnit unit = switch (basis) {
        case "PER_KG" -> PriceUnit.MASS;
        case "PER_L" -> PriceUnit.VOLUME;
        default -> unitOf(source);
      };
      if (unit == targetUnit) {
        continue;
      }
      if (unit == PriceUnit.PACKAGE && !target.isVariableWeight() && targetUnit != PriceUnit.PACKAGE) {
        adopt = true;
        continue;
      }
      throw new ValidationException(ErrorCode.RECEIPT_ITEM_REMATCH_UNIT_MISMATCH);
    }
    return adopt;
  }

  /**
   * Ceny za balení ({@code PACKAGE}/{@code PER_PIECE}) zboží z účtenky převezmou gramáž cíle —
   * jen ty, které se právě stěhují: jednoho autora, nebo všechny ({@code submitterId == null}).
   */
  void adoptTargetNetContent(Long sourceId, Product target, Long submitterId) {
    String sql = """
        UPDATE core.price_observation
           SET net_content_base = ? * CASE WHEN price_kind = 'MULTIBUY' THEN COALESCE(multibuy_qty, 1) ELSE 1 END
         WHERE product_id = ? AND quantity_basis IN ('PACKAGE', 'PER_PIECE')
        """;
    if (submitterId == null) {
      jdbcTemplate.update(sql, target.getNetContentBase(), sourceId);
    } else {
      jdbcTemplate.update(sql + " AND submitter_id = ?", target.getNetContentBase(), sourceId, submitterId);
    }
  }

  /**
   * Původní snapshot ceny za balení zdroje bez gramáže — {@code net_content_base = 1} (u MULTIBUY
   * počet kusů). Volá se při změně hlasu, kdy se dříve převedené ceny vracejí na zdroj.
   */
  static String packageBaseSql() {
    return "CASE WHEN price_kind = 'MULTIBUY' THEN COALESCE(multibuy_qty, 1) ELSE 1 END";
  }
}
