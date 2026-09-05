package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.service.ResolvedProductName;

import java.math.BigDecimal;
import java.util.List;

/**
 * Náhled zboží z OFF pro formulář nového zboží. {@code name} je v jazyce klienta, jen když ho
 * OFF v tom jazyce má — jinak jde o nejbližší náhradu a {@code nameLang} říká, o jaký jazyk
 * doopravdy jde, aby formulář mohl upozornit „název je v němčině" (docs/lokalizace.md).
 *
 * <p>{@code names} vrací {@link ResolvedProductName} přímo — GraphQL typ {@code ProductName}
 * se na něj mapuje podle názvů polí, takže vlastní kopie záznamu v této vrstvě by nic nepřidala.
 */
public record ExternalProductCandidate(
    String code, String name, String nameLang, List<ResolvedProductName> names, String brandName,
    Category category, UnitBase unitBase, BigDecimal netContentValue, NetContentUom netContentUom,
    ExternalProductImage image, List<ExternalProductImage> images, String sourceUrl, String attribution) {
}
