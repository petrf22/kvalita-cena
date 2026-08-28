package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;

/** Potvrzený OFF náhled; hodnoty odlišné od snapshotu se uloží jako osobní patch. */
public record CreateProductFromOffInput(
    String code, String name, String brandName, Long categoryId, UnitBase unitBase,
    BigDecimal netContentValue, NetContentUom netContentUom, Integer piecesInPack,
    Boolean isVariableWeight) {
}
