package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code name} je primární název v jazyce {@code nameLang} (prázdné = jazyk requestu),
 * {@code names} nese případné DALŠÍ jazyky (docs/lokalizace.md). Jazyk se nikdy nehádá
 * z textu — zná ho klient, ne server.
 */
public record CreateProductInput(
    String name,
    String nameLang,
    List<ProductNameInput> names,
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
