package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Přepočet gramáže/objemu na základní jednotku (kg/l/ks), ze které se počítá jednotková cena
 * (docs/datovy-model.md). Sdílené mezi {@link ProductCatalogService#create} a
 * {@link CatalogEditService#updateProduct} — obě cesty musí počítat úplně stejně, jinak by
 * uživatelská úprava gramáže dala jinou jednotkovou cenu než založení nového zboží se stejnými
 * čísly.
 */
final class NetContentCalculator {

  private NetContentCalculator() {
  }

  static BigDecimal computeNetContentBase(UnitBase unitBase, BigDecimal value, NetContentUom uom,
      boolean variableWeight) {
    // Váhové zboží se prodává za jednotkovou cenu bez pevného balení (stejná konvence jako
    // PER_KG/PER_L v PriceObservationService) — stejně tak bezkódová položka bez zadané gramáže.
    if (variableWeight || value == null) {
      return BigDecimal.ONE;
    }
    NetContentUom effectiveUom = uom != null ? uom : impliedUom(unitBase);
    validateUomMatchesUnitBase(unitBase, effectiveUom);
    return switch (effectiveUom) {
      case G, ML -> value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
      case KG, L, PCS -> value;
    };
  }

  private static NetContentUom impliedUom(UnitBase unitBase) {
    return switch (unitBase) {
      case MASS -> NetContentUom.KG;
      case VOLUME -> NetContentUom.L;
      case COUNT -> NetContentUom.PCS;
    };
  }

  private static void validateUomMatchesUnitBase(UnitBase unitBase, NetContentUom uom) {
    boolean ok = switch (unitBase) {
      case MASS -> uom == NetContentUom.G || uom == NetContentUom.KG;
      case VOLUME -> uom == NetContentUom.ML || uom == NetContentUom.L;
      case COUNT -> uom == NetContentUom.PCS;
    };
    if (!ok) {
      // Symbolická jména enumu do args, NIKDY přeložený popisek (viz AppException) — klient
      // si hezčí verzi složí z extensions.params vlastním enum.netContentUom.* klíčem.
      throw new ValidationException(ErrorCode.UOM_MISMATCH, uom.name(), unitBase.name());
    }
  }
}
