package cz.kvalitacena.controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GDPR export vlastních dat (docs/soukromi.md, "GDPR" — {@code GET /api/me/export}). Ruční DTO,
 * ne přímo JPA entity — {@code PriceObservation} má lazy {@code submitter}/{@code product}/
 * {@code store} (se serializací entity napřímo by appka vystavila i cizí interní pole jako
 * {@code email_hash}), a i tam, kde entita nese jen skalární pole ({@code ProductUserEdit},
 * {@code StoreUserEdit}, {@code ProductQualityRating}), appka drží stejnou konvenci jako
 * GraphQL vrstva — vlastní {@code record} obálku, nikdy syrovou entitu ven z controlleru.
 */
public record AccountExportResponse(
    AccountInfo account,
    ProfileExport profile,
    List<ObservationExport> priceObservations,
    List<QualityRatingExport> qualityRatings,
    List<ProductEditExport> productEdits,
    List<StoreEditExport> storeEdits) {

  public record AccountInfo(String publicHandle, String displayName, String loginEmail,
      OffsetDateTime createdAt, String locale, String country) {
  }

  /** {@code null} pole = uživatel je nikdy nevyplnil (docs/soukromi.md, "Profil uživatele a viditelnost"). */
  public record ProfileExport(String firstName, String lastName, String phone, String contactEmail,
      String visibility, boolean hasAvatar) {
  }

  /**
   * Jen observace stále navázané na tento účet — po 180 dnech appka vazbu sama zruší
   * (docs/soukromi.md), export tak přirozeně ukazuje stejné okno jako appka sama.
   */
  public record ObservationExport(String productName, String storeName, BigDecimal priceAmount,
      String currency, String priceKind, OffsetDateTime observedAt) {
  }

  public record QualityRatingExport(String productName, short grade) {
  }

  /** {@code null} pole = appka je při úpravě nezměnila; {@code clearedFields} = uživatel je vymazal. */
  public record ProductEditExport(String productName, String name, Long categoryId, String unitBase,
      BigDecimal netContentValue, String netContentUom, Integer piecesInPack, Boolean variableWeight,
      List<String> clearedFields, OffsetDateTime updatedAt) {
  }

  public record StoreEditExport(String storeName, String name, String street, String city,
      String postalCode, String country, String ico, BigDecimal lat, BigDecimal lon,
      List<String> clearedFields, OffsetDateTime updatedAt) {
  }
}
