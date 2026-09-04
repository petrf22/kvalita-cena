package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;

public record CreateProductInput(
    String name,
    String brandName,
    Long categoryId,
    UnitBase unitBase,
    BigDecimal netContentValue,
    NetContentUom netContentUom,
    Integer piecesInPack,
    Boolean isVariableWeight,
    Long storeId,
    String code) {
}
