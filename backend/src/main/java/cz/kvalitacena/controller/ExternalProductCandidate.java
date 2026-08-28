package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;

import java.math.BigDecimal;

public record ExternalProductCandidate(
    String code, String name, String brandName, Category category, UnitBase unitBase,
    BigDecimal netContentValue, NetContentUom netContentUom, ExternalProductImage image,
    String sourceUrl, String attribution) {
}
