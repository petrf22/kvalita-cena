package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.UnitBase;
import org.springframework.stereotype.Component;

/** Přijímá jen explicitní jednotky podporované katalogem; volný text se nikdy nehádá. */
@Component
public class OffNetContentConverter {

  public OffNetContent convert(OffProduct product) {
    if (product == null || product.getProductQuantity() == null || product.getProductQuantityUnit() == null) {
      return null;
    }
    NetContentUom uom;
    try {
      uom = NetContentUom.valueOf(product.getProductQuantityUnit());
    } catch (IllegalArgumentException e) {
      return null;
    }
    UnitBase unitBase = switch (uom) {
      case G, KG -> UnitBase.MASS;
      case ML, L -> UnitBase.VOLUME;
      case PCS -> null;
    };
    if (unitBase == null) return null;
    try {
      return new OffNetContent(unitBase, product.getProductQuantity(), uom,
          NetContentCalculator.computeNetContentBase(unitBase, product.getProductQuantity(), uom, false));
    } catch (RuntimeException e) {
      return null;
    }
  }
}
