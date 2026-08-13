package cz.kvalitacena.service;

import cz.kvalitacena.config.OtpProperties;
import cz.kvalitacena.controller.AccountExportResponse;
import cz.kvalitacena.controller.AccountExportResponse.AccountInfo;
import cz.kvalitacena.controller.AccountExportResponse.ObservationExport;
import cz.kvalitacena.controller.AccountExportResponse.ProductEditExport;
import cz.kvalitacena.controller.AccountExportResponse.ProfileExport;
import cz.kvalitacena.controller.AccountExportResponse.QualityRatingExport;
import cz.kvalitacena.controller.AccountExportResponse.StoreEditExport;
import cz.kvalitacena.db.entity.AccountDeleteMode;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ChallengePurpose;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.LoginChallenge;
import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductQualityRating;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.entity.RecomputeReason;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.entity.UserProfile;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.LoginChallengeRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository.ObservationCell;
import cz.kvalitacena.db.repo.ProductQualityRatingRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductUserEditRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import cz.kvalitacena.db.repo.UserProfileRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.OtpRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GDPR export a výmaz účtu (docs/soukromi.md, "GDPR") — poslední kus etapa-1 slibu, který
 * dosud existoval jen jako {@code ChallengePurpose.DELETE_ACCOUNT} hák v enumu, beze zbytku
 * implementace.
 *
 * <p>Výmaz je stejný dvoukrokový OTP tok jako {@link EmailChangeService}, jen na už vlastněnou
 * adresu (žádné riziko hijacku cizí schránky, žádná potřeba nerozlišitelné odpovědi) — kód
 * dokazuje, že žádost podává skutečný vlastník účtu, ne jen někdo s ukradeným access tokenem.
 *
 * <p>{@link AccountDeleteMode#ANONYMIZE} nevyžaduje ŽÁDNOU zvláštní práci nad
 * {@code price_observation} — {@code fk_price_observation_submitter} je už
 * {@code ON DELETE SET NULL}, takže smazání {@link AppUser} řádku observace samo anonymizuje,
 * stejným mechanismem jako denní {@link PseudonymizationService}. {@link
 * AccountDeleteMode#DELETE_CONTENT} naopak observace SKUTEČNĚ maže (ne jen nuluje vazbu), proto
 * to musí proběhnout explicitně PŘED smazáním uživatele a s ručním zařazením dotčených buněk do
 * {@code agg.recompute_queue} — bulk DELETE žádný přepočet sám nespustí.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

  private final AppUserRepository appUserRepository;
  private final UserProfileRepository userProfileRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final ProductQualityRatingRepository qualityRatingRepository;
  private final ProductUserEditRepository productUserEditRepository;
  private final StoreUserEditRepository storeUserEditRepository;
  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final MediaRepository mediaRepository;
  private final MediaStorage mediaStorage;
  private final LoginChallengeRepository challengeRepository;
  private final EmailCipher emailCipher;
  private final PasswordEncoder codeEncoder; // Argon2id, stejný bean jako OtpService/EmailChangeService
  private final OtpRateLimiter rateLimiter;
  private final OtpProperties otpProperties;
  private final OtpMailSender mailSender;
  private final PriceAggregationService priceAggregationService;
  private final SecureRandom secureRandom = new SecureRandom();

  @Transactional(readOnly = true)
  public AccountExportResponse exportData(AppUser user) {
    UserProfile profile = userProfileRepository.findById(user.getId()).orElse(null);

    List<PriceObservation> observations =
        priceObservationRepository.findBySubmitterIdWithProductAndStore(user.getId());
    List<ObservationExport> observationExports = observations.stream()
        .map(o -> new ObservationExport(o.getProduct().getName(), o.getStore().getName(),
            o.getPriceAmount(), o.getCurrency(), o.getPriceKind().name(), o.getObservedAt()))
        .toList();

    List<ProductQualityRating> ratings = qualityRatingRepository.findByUserId(user.getId());
    Map<Long, String> productNamesForRatings = productNames(ratings.stream().map(ProductQualityRating::getProductId).toList());
    List<QualityRatingExport> ratingExports = ratings.stream()
        .map(r -> new QualityRatingExport(productNamesForRatings.get(r.getProductId()), r.getGrade()))
        .toList();

    List<ProductUserEdit> productEdits = productUserEditRepository.findByUserId(user.getId());
    Map<Long, String> productNamesForEdits = productNames(productEdits.stream().map(ProductUserEdit::getProductId).toList());
    List<ProductEditExport> productEditExports = productEdits.stream()
        .map(e -> new ProductEditExport(productNamesForEdits.get(e.getProductId()), e.getName(),
            e.getCategoryId(), e.getUnitBase(), e.getNetContentValue(), e.getNetContentUom(),
            e.getPiecesInPack(), e.getVariableWeight(), e.getClearedFields(), e.getUpdatedAt()))
        .toList();

    List<StoreUserEdit> storeEdits = storeUserEditRepository.findByUserId(user.getId());
    Map<Long, String> storeNamesForEdits = storeNames(storeEdits.stream().map(StoreUserEdit::getStoreId).toList());
    List<StoreEditExport> storeEditExports = storeEdits.stream()
        .map(e -> new StoreEditExport(storeNamesForEdits.get(e.getStoreId()), e.getName(), e.getStreet(),
            e.getCity(), e.getPostalCode(), e.getCountry(), e.getIco(), e.getLat(), e.getLon(),
            e.getClearedFields(), e.getUpdatedAt()))
        .toList();

    AccountInfo accountInfo = new AccountInfo(user.getPublicHandle(), user.getDisplayName(),
        emailCipher.decrypt(user.getEmailEnc()), user.getCreatedAt(), user.getLocale(), user.getCountry());

    ProfileExport profileExport = new ProfileExport(
        decrypt(profile == null ? null : profile.getFirstNameEnc()),
        decrypt(profile == null ? null : profile.getLastNameEnc()),
        decrypt(profile == null ? null : profile.getPhoneEnc()),
        decrypt(profile == null ? null : profile.getContactEmailEnc()),
        profile == null ? null : profile.getVisibility().name(),
        profile != null && profile.getAvatarMediaId() != null);

    return new AccountExportResponse(accountInfo, profileExport, observationExports, ratingExports,
        productEditExports, storeEditExports);
  }

  public record RequestResult(UUID challengeUid, long expiresInSec, long resendAfterSec) {
  }

  /** Kód jde vždy na už vlastněnou přihlašovací adresu — na rozdíl od EmailChangeService tu není co hijacknout. */
  @Transactional
  public RequestResult requestDelete(AppUser currentUser, ClientKind clientKind, String ipAddress) {
    if (!rateLimiter.tryAcquireForRequest(currentUser.getEmailHash(), ipAddress)) {
      throw new TooManyRequestsException();
    }

    challengeRepository.invalidateActiveChallenges(currentUser.getEmailHash(), OffsetDateTime.now());

    String code = generateSixDigitCode();
    LoginChallenge challenge = LoginChallenge.builder()
        .emailHash(currentUser.getEmailHash())
        .codeHash(codeEncoder.encode(code))
        .purpose(ChallengePurpose.DELETE_ACCOUNT)
        .expiresAt(OffsetDateTime.now().plus(otpProperties.getCodeTtl()))
        .maxAttempts((short) otpProperties.getMaxAttempts())
        .ipHash(ipAddress == null ? null : emailCipher.hash(ipAddress))
        .clientKind(clientKind)
        .build();
    challenge = challengeRepository.save(challenge);

    Locale locale = currentUser.getLocale() != null && !currentUser.getLocale().isBlank()
        ? Locale.forLanguageTag(currentUser.getLocale())
        : LocaleContextHolder.getLocale();
    String rawEmail = emailCipher.decrypt(currentUser.getEmailEnc());

    if (otpProperties.isMailEnabled()) {
      mailSender.sendAccountDeleteCode(rawEmail, code, locale);
    } else {
      log.info("[DEV] Kód pro výmaz účtu {} (challenge {}): {}",
          emailCipher.normalize(rawEmail), challenge.getChallengeUid(), code);
    }

    return new RequestResult(challenge.getChallengeUid(), otpProperties.getCodeTtl().toSeconds(),
        otpProperties.getResendCooldown().toSeconds());
  }

  /**
   * Nevratné. Pořadí je záměrné: nejdřív smazat SOUBORY fotek (dokud ještě víme, které
   * {@code storageKey} k uživateli patří), teprve pak DB řádek {@link AppUser} — ten smaže
   * kaskádou zbytek ({@code user_profile}, {@code product_quality_rating},
   * {@code product_user_edit}/{@code store_user_edit}, {@code record_flag}, {@code media},
   * {@code refresh_token}; viz FK přehled u jednotlivých Liquibase changelogů). Opačné pořadí
   * by po neúspěšném mazání souboru nechalo appku s DB řádkem ukazujícím na soubor, který už
   * mezitím zmizel.
   */
  @Transactional
  public void confirmDelete(AppUser currentUser, UUID challengeUid, String code, AccountDeleteMode mode) {
    LoginChallenge challenge = challengeRepository.findByChallengeUidAndConsumedAtIsNull(challengeUid)
        .filter(c -> c.getPurpose() == ChallengePurpose.DELETE_ACCOUNT)
        .orElseThrow(() -> new ValidationException(ErrorCode.ACCOUNT_DELETE_INVALID_CHALLENGE));

    if (!Arrays.equals(currentUser.getEmailHash(), challenge.getEmailHash())) {
      throw new ValidationException(ErrorCode.ACCOUNT_DELETE_INVALID_CHALLENGE);
    }
    if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
      throw new ValidationException(ErrorCode.ACCOUNT_DELETE_INVALID_CHALLENGE);
    }

    int updated = challengeRepository.incrementAttempts(challenge.getId());
    if (updated == 0) {
      challenge.setConsumedAt(OffsetDateTime.now());
      challengeRepository.save(challenge);
      throw new ValidationException(ErrorCode.ACCOUNT_DELETE_INVALID_CHALLENGE);
    }
    if (!codeEncoder.matches(code, challenge.getCodeHash())) {
      throw new ValidationException(ErrorCode.ACCOUNT_DELETE_INVALID_CHALLENGE);
    }
    challenge.setConsumedAt(OffsetDateTime.now());
    challengeRepository.save(challenge);

    if (mode == AccountDeleteMode.DELETE_CONTENT) {
      List<ObservationCell> cells = priceObservationRepository.findDistinctProductStoreBySubmitterId(currentUser.getId());
      priceObservationRepository.deleteBySubmitterId(currentUser.getId());
      cells.forEach(cell ->
          priceAggregationService.enqueueRecompute(cell.getProductId(), cell.getStoreId(), RecomputeReason.MODERATION));
    }
    // ANONYMIZE: nic extra netřeba — fk_price_observation_submitter je ON DELETE SET NULL,
    // smazání uživatele níž observace samo anonymizuje.

    for (Media media : mediaRepository.findByUploadedByUserId(currentUser.getId())) {
      mediaStorage.delete(media.getStorageKey());
    }

    appUserRepository.delete(currentUser);
    log.info("Účet {} smazán (režim {}).", currentUser.getPublicHandle(), mode);
  }

  private Map<Long, String> productNames(List<Long> productIds) {
    return productRepository.findAllById(productIds).stream()
        .collect(Collectors.toMap(Product::getId, Product::getName));
  }

  private Map<Long, String> storeNames(List<Long> storeIds) {
    return storeRepository.findAllById(storeIds).stream()
        .collect(Collectors.toMap(Store::getId, Store::getName));
  }

  private String decrypt(byte[] encrypted) {
    return encrypted == null ? null : emailCipher.decryptValue(encrypted);
  }

  private String generateSixDigitCode() {
    int value = 100_000 + secureRandom.nextInt(900_000);
    return Integer.toString(value);
  }
}
