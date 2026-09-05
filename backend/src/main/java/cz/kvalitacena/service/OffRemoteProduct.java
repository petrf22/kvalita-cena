package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.OffProductImage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Normalizovaná podmnožina odpovědi OFF API; pořád jde o cizí data určená jen do off.*.
 *
 * <p>{@code productName} je „hlavní" varianta z OFF ({@code product_name}) a je to POČÍTANÉ
 * pole — u produktu s {@code lang='en'} umí vrátit český text — takže se z něj jazyk poznat
 * nedá. Jazyk nese teprve {@code names} ({@code product_name_<lc>}), a {@code lang} říká, jaký
 * je hlavní jazyk celého záznamu.
 *
 * <p>{@code nameLocales} jsou jazyky, o které si klient v tomhle dotazu ŘEKL (ne ty, které OFF
 * vrátil) — ukládají se do snapshotu, aby se po rozšíření appky o další jazyk poznalo, že
 * záznam je potřeba stáhnout znovu.
 */
public record OffRemoteProduct(
    String lang,
    String productName,
    Map<String, String> names,
    List<String> nameLocales,
    String brandName,
    BigDecimal productQuantity,
    String productQuantityUnit,
    List<String> categoryTags,
    String imageFrontUrl,
    String imageFrontSmallUrl,
    List<OffProductImage> images,
    List<String> additivesTags,
    Long revision,
    OffsetDateTime updatedAt) {
}
