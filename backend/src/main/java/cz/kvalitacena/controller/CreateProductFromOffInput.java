package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;
import java.util.List;

/**
 * Potvrzený OFF náhled; hodnoty odlišné od snapshotu se uloží jako osobní patch — s výjimkou
 * názvu v jazyce, který snapshot vůbec nemá. Ten je globální (docs/lokalizace.md): český
 * překlad německého názvu z OFF je doplnění díry, ne nesouhlas, a jinak by na tutéž němčinu
 * narazil každý další česky mluvící uživatel.
 */
public record CreateProductFromOffInput(
    String code, String name, String nameLang, List<ProductNameInput> names,
    String brandName, Long categoryId, UnitBase unitBase,
    BigDecimal netContentValue, NetContentUom netContentUom, Integer piecesInPack,
    Boolean isVariableWeight) {
}
