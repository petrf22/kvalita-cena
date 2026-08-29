package cz.kvalitacena.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Normalizovaná podmnožina odpovědi OFF API; pořád jde o cizí data určená jen do off.*. */
public record OffRemoteProduct(
    String productName,
    String brandName,
    BigDecimal productQuantity,
    String productQuantityUnit,
    List<String> categoryTags,
    String imageFrontUrl,
    String imageFrontSmallUrl,
    List<String> additivesTags,
    Long revision,
    OffsetDateTime updatedAt) {
}
