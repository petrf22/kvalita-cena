package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;

/** Bezpečně převedené množství OFF do katalogového modelu aplikace. */
public record OffNetContent(UnitBase unitBase, BigDecimal value, NetContentUom uom, BigDecimal base) {
}
