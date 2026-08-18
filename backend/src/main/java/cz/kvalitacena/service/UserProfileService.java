package cz.kvalitacena.service;

import cz.kvalitacena.controller.Photo;
import cz.kvalitacena.controller.Profile;
import cz.kvalitacena.controller.ProfileFieldAudience;
import cz.kvalitacena.controller.UpdateProfileInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.UserProfileFieldVisibilityRepository;
import cz.kvalitacena.db.repo.UserProfileRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.ViewerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Profil přihlášeného uživatele (jméno, příjmení, telefon, kontaktní e-mail, přezdívka, avatar,
 * viditelnost) — vědomá změna dřívějšího rozhodnutí "auth.app_user nemá pole pro jméno, adresu
 * ani telefon" (docs/soukromi.md, "Profil uživatele a viditelnost"). Textová PII je šifrovaná
 * stejným AES-256-GCM jako {@code email_enc} ({@link EmailCipher#encryptValue}), avatar
 * (core.media) šifrovaný NENÍ.
 *
 * <p>{@link #load} vrací vždy PLNÝ pohled — je to jen pro vlastníka (dotaz {@code me}), nikdy se
 * nefiltruje podle {@code visibility}. {@link #isFieldVisible} je naopak jediné místo pravdy pro
 * to, co uvidí NĚKDO JINÝ (dnes už použité pro avatar v {@code MediaController}, do budoucna i pro
 * případný dotaz na cizí profil): {@code ANONYMOUS} blokuje vše, jinak rozhoduje matice
 * {@code user_profile_field_visibility} nezávisle na tom, jestli je celkový režim PUBLIC nebo
 * FRIENDS. Skupiny důvěry (přátelé) v etapě 1 neexistují, takže řádky s {@link Audience#FRIENDS}
 * se zatím nikdy neuplatní — to je očekávané, ne chyba (docs/datovy-model.md, etapa 2/3).
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

  private static final int MAX_NAME_LENGTH = 80;
  // Odpovídá délce sloupce auth.app_user.display_name (viz AppUser.displayName).
  private static final int MAX_DISPLAY_NAME_LENGTH = 40;
  private static final Pattern PHONE_PATTERN = Pattern.compile("^[+]?[0-9 ()-]{6,20}$");
  private static final Pattern CONTACT_EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private final UserProfileRepository userProfileRepository;
  private final UserProfileFieldVisibilityRepository fieldVisibilityRepository;
  private final AppUserRepository appUserRepository;
  private final MediaRepository mediaRepository;
  private final MediaService mediaService;
  private final EmailCipher emailCipher;

  /** Vždy plný pohled vlastníka na vlastní profil — nikdy se nefiltruje podle visibility. */
  @Transactional(readOnly = true)
  public Profile load(AppUser user) {
    UserProfile profile = userProfileRepository.findById(user.getId()).orElse(null);
    List<ProfileFieldAudience> visibleFields = fieldVisibilityRepository.findAllByUserId(user.getId()).stream()
        .map(row -> new ProfileFieldAudience(row.getField(), row.getAudience()))
        .toList();
    Photo avatar = loadAvatar(user, profile);

    return new Profile(
        decrypt(profile == null ? null : profile.getFirstNameEnc()),
        decrypt(profile == null ? null : profile.getLastNameEnc()),
        decrypt(profile == null ? null : profile.getPhoneEnc()),
        decrypt(profile == null ? null : profile.getContactEmailEnc()),
        emailCipher.decrypt(user.getEmailEnc()),
        profile == null ? ProfileVisibility.ANONYMOUS : profile.getVisibility(),
        visibleFields,
        avatar);
  }

  @Transactional
  public Profile update(AppUser user, UpdateProfileInput input) {
    UserProfile profile = userProfileRepository.findById(user.getId())
        .orElseGet(() -> UserProfile.builder().userId(user.getId()).build());

    applyEncryptedField(input.clearFirstName(), input.firstName(), this::validateName, profile::setFirstNameEnc);
    applyEncryptedField(input.clearLastName(), input.lastName(), this::validateName, profile::setLastNameEnc);
    applyEncryptedField(input.clearPhone(), input.phone(), this::validatePhone, profile::setPhoneEnc);
    applyEncryptedField(
        input.clearContactEmail(), input.contactEmail(), this::validateContactEmail, profile::setContactEmailEnc);

    if (Boolean.TRUE.equals(input.clearDisplayName())) {
      user.setDisplayName(null);
    } else if (input.displayName() != null) {
      String trimmed = input.displayName().trim();
      user.setDisplayName(trimmed.isEmpty() ? null : validateDisplayName(trimmed));
    }

    if (input.visibility() != null) {
      profile.setVisibility(input.visibility());
    }

    userProfileRepository.save(profile);
    appUserRepository.save(user);

    // null = matice se nemění, JAKÝKOLI seznam (i prázdný) ji celou nahradí (viz UpdateProfileInput).
    if (input.visibleFields() != null) {
      fieldVisibilityRepository.deleteAllByUserId(user.getId());
      List<UserProfileFieldVisibility> rows = input.visibleFields().stream()
          .distinct()
          .map(entry -> UserProfileFieldVisibility.builder()
              .userId(user.getId())
              .field(entry.field())
              .audience(entry.audience())
              .build())
          .toList();
      fieldVisibilityRepository.saveAll(rows);
    }

    return load(user);
  }

  /**
   * Jediné místo pravdy pro to, co z profilu {@code ownerUserId} uvidí NĚKDO JINÝ (dnes avatar
   * v {@code MediaController.serve}, do budoucna případný dotaz na cizí profil).
   */
  @Transactional(readOnly = true)
  public boolean isFieldVisible(Long ownerUserId, ProfileField field, ViewerContext viewer) {
    if (viewer.userId() != null && viewer.userId().equals(ownerUserId)) {
      return true; // vlastník vidí vždy vše, i v režimu ANONYMOUS
    }
    UserProfile profile = userProfileRepository.findById(ownerUserId).orElse(null);
    if (profile == null || profile.getVisibility() == ProfileVisibility.ANONYMOUS) {
      return false;
    }
    if (fieldVisibilityRepository.existsByUserIdAndFieldAndAudience(ownerUserId, field, Audience.PUBLIC)) {
      return true;
    }
    // TODO (etapa 2/3): friendshipService.areFriends(ownerUserId, viewer.userId()) && existsByUserIdAndFieldAndAudience(…, FRIENDS).
    // Skupiny důvěry zatím neexistují (docs/datovy-model.md), takže žádný viewer nikdy není "přítel".
    return false;
  }

  private Photo loadAvatar(AppUser user, UserProfile profile) {
    if (profile == null || profile.getAvatarMediaId() == null) {
      return null;
    }
    Media media = mediaRepository.findById(profile.getAvatarMediaId()).orElse(null);
    if (media == null) {
      return null;
    }
    return mediaService.toPhoto(media, new ViewerContext(user.getPublicUid(), user.getId(), false, user.isModerator()));
  }

  private void applyEncryptedField(Boolean clear, String rawValue, Function<String, String> validator,
      Consumer<byte[]> setter) {
    if (Boolean.TRUE.equals(clear)) {
      setter.accept(null);
      return;
    }
    if (rawValue == null) {
      return;
    }
    String trimmed = rawValue.trim();
    setter.accept(trimmed.isEmpty() ? null : emailCipher.encryptValue(validator.apply(trimmed)));
  }

  private String decrypt(byte[] encrypted) {
    return encrypted == null ? null : emailCipher.decryptValue(encrypted);
  }

  private String validateName(String trimmedValue) {
    if (trimmedValue.length() > MAX_NAME_LENGTH) {
      throw new ValidationException(ErrorCode.PROFILE_NAME_TOO_LONG, MAX_NAME_LENGTH);
    }
    return trimmedValue;
  }

  private String validateDisplayName(String trimmedValue) {
    if (trimmedValue.length() > MAX_DISPLAY_NAME_LENGTH) {
      throw new ValidationException(ErrorCode.PROFILE_DISPLAY_NAME_TOO_LONG, MAX_DISPLAY_NAME_LENGTH);
    }
    return trimmedValue;
  }

  private String validatePhone(String trimmedValue) {
    if (!PHONE_PATTERN.matcher(trimmedValue).matches()) {
      throw new ValidationException(ErrorCode.PROFILE_PHONE_INVALID);
    }
    return trimmedValue;
  }

  private String validateContactEmail(String trimmedValue) {
    if (!CONTACT_EMAIL_PATTERN.matcher(trimmedValue).matches()) {
      throw new ValidationException(ErrorCode.PROFILE_EMAIL_INVALID);
    }
    return trimmedValue;
  }
}
