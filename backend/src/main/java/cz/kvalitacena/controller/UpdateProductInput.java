package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;
import java.util.List;

/**
 * Patch nad core.product (core.product_user_edit) — {@code null} u pole znamená "nezměněno",
 * ne "smazat" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"). {@code
 * clearBrand}/{@code clearPiecesInPack} řeší jediné dvě volitelné hodnoty, které jde smazat
 * (na rozdíl od name/categoryId/unitBase, ty se nikdy nevymazávají, jen mění).
 *
 * <p>{@code name} je název v jazyce {@code nameLang} (prázdné = jazyk requestu), {@code names}
 * nese DALŠÍ jazyky — jeden formulář tak umí zároveň opravit český název a doplnit chybějící
 * německý. Kam který název skončí (globálně vs. osobní patch) rozhoduje
 * {@code ProductNameWriter}, ne klient.
 */
public record UpdateProductInput(
    String name,
    String nameLang,
    List<ProductNameInput> names,
    String brandName,
    Boolean clearBrand,
    Long categoryId,
    UnitBase unitBase,
    BigDecimal netContentValue,
    NetContentUom netContentUom,
    Integer piecesInPack,
    Boolean clearPiecesInPack,
    Boolean isVariableWeight) {
}
