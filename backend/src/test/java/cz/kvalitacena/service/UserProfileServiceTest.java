package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProfileFieldAudience;
import cz.kvalitacena.controller.UpdateProfileInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.db.repo.UserProfileFieldVisibilityRepository;
import cz.kvalitacena.db.repo.UserProfileRepository;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.EmailCipher;
import cz.kvalitacena.security.ViewerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Profil je nepovinný a šifrovaný (docs/soukromi.md, "Profil uživatele a viditelnost") — testy
 * ověřují konvenci "null = nezměněno, clearX = smazat" (stejnou jako updateProduct/updateStore)
 * a hlavně predikát {@link UserProfileService#isFieldVisible}: ANONYMOUS blokuje vše i s
 * vyplněnou maticí, vlastník vidí vždy vše, FRIENDS řádky se v etapě 1 nikdy neuplatní.
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private UserProfileRepository userProfileRepository;
  @Mock
  private UserProfileFieldVisibilityRepository fieldVisibilityRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private MediaRepository mediaRepository;
  @Mock
  private MediaService mediaService;
  @Mock
  private EmailCipher emailCipher;

  private UserProfileService service;
  private AppUser user;

  @BeforeEach
  void setUp() {
    service = new UserProfileService(
        userProfileRepository, fieldVisibilityRepository, appUserRepository, mediaRepository, mediaService,
        emailCipher);
    user = AppUser.builder().id(USER_ID).publicUid(PUBLIC_UID)
        .emailEnc("login@example.com".getBytes(StandardCharsets.UTF_8)).build();

    lenient().when(emailCipher.encryptValue(any())).thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes(StandardCharsets.UTF_8));
    lenient().when(emailCipher.decryptValue(any())).thenAnswer(inv -> new String((byte[]) inv.getArgument(0), StandardCharsets.UTF_8));
    lenient().when(emailCipher.decrypt(any())).thenAnswer(inv -> new String((byte[]) inv.getArgument(0), StandardCharsets.UTF_8));
    lenient().when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());
    lenient().when(fieldVisibilityRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
    lenient().when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private UpdateProfileInput emptyInput() {
    return new UpdateProfileInput(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void settingFirstNameEncryptsAndTrimsIt() {
    UpdateProfileInput input = new UpdateProfileInput(
        "  Jan  ", null, null, null, null, null, null, null, null, null, null, null);

    service.update(user, input);

    ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
    verify(userProfileRepository).save(captor.capture());
    assertThat(new String(captor.getValue().getFirstNameEnc(), StandardCharsets.UTF_8)).isEqualTo("Jan");
  }

  @Test
  void clearFirstNameSetsItToNullEvenIfValueIsAlsoSent() {
    UpdateProfileInput input = new UpdateProfileInput(
        "Jan", true, null, null, null, null, null, null, null, null, null, null);

    service.update(user, input);

    ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
    verify(userProfileRepository).save(captor.capture());
    assertThat(captor.getValue().getFirstNameEnc()).isNull();
  }

  @Test
  void blankValueAfterTrimIsTreatedAsClear() {
    UpdateProfileInput input = new UpdateProfileInput(
        "   ", null, null, null, null, null, null, null, null, null, null, null);

    service.update(user, input);

    ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
    verify(userProfileRepository).save(captor.capture());
    assertThat(captor.getValue().getFirstNameEnc()).isNull();
  }

  @Test
  void nameLongerThanEightyCharsIsRejected() {
    UpdateProfileInput input = new UpdateProfileInput(
        "x".repeat(81), null, null, null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> service.update(user, input)).isInstanceOf(ValidationException.class);
  }

  @Test
  void displayNameLongerThanFortyCharsIsRejected() {
    UpdateProfileInput input = new UpdateProfileInput(
        null, null, null, null, "x".repeat(41), null, null, null, null, null, null, null);

    assertThatThrownBy(() -> service.update(user, input)).isInstanceOf(ValidationException.class);
  }

  @Test
  void invalidPhoneFormatIsRejected() {
    UpdateProfileInput input = new UpdateProfileInput(
        null, null, null, null, null, null, "not-a-phone!!", null, null, null, null, null);

    assertThatThrownBy(() -> service.update(user, input)).isInstanceOf(ValidationException.class);
  }

  @Test
  void invalidContactEmailFormatIsRejected() {
    UpdateProfileInput input = new UpdateProfileInput(
        null, null, null, null, null, null, null, null, "not-an-email", null, null, null);

    assertThatThrownBy(() -> service.update(user, input)).isInstanceOf(ValidationException.class);
  }

  @Test
  void nullVisibleFieldsLeavesMatrixUntouched() {
    service.update(user, emptyInput());

    verify(fieldVisibilityRepository, never()).deleteAllByUserId(any());
    verify(fieldVisibilityRepository, never()).saveAll(any());
  }

  @Test
  void nonNullVisibleFieldsReplacesTheWholeMatrix() {
    UpdateProfileInput input = new UpdateProfileInput(null, null, null, null, null, null, null, null, null, null,
        ProfileVisibility.PUBLIC, List.of(new ProfileFieldAudience(ProfileField.FIRST_NAME, Audience.PUBLIC)));

    service.update(user, input);

    verify(fieldVisibilityRepository).deleteAllByUserId(USER_ID);
    verify(fieldVisibilityRepository).saveAll(any());
  }

  @Test
  void emptyVisibleFieldsListAlsoClearsTheMatrix() {
    UpdateProfileInput input = new UpdateProfileInput(null, null, null, null, null, null, null, null, null, null,
        null, List.of());

    service.update(user, input);

    verify(fieldVisibilityRepository).deleteAllByUserId(USER_ID);
  }

  @Test
  void ownerAlwaysSeesEveryFieldEvenWhenAnonymous() {
    // Vlastník je poznán rovnou z ViewerContext.userId() — profil se vůbec nenačítá.
    ViewerContext owner = new ViewerContext(PUBLIC_UID, USER_ID, false);

    assertThat(service.isFieldVisible(USER_ID, ProfileField.PHONE, owner)).isTrue();
    verifyNoInteractions(userProfileRepository);
  }

  @Test
  void anonymousVisibilityBlocksStrangersEvenWithMatrixRows() {
    when(userProfileRepository.findById(USER_ID))
        .thenReturn(Optional.of(UserProfile.builder().userId(USER_ID).visibility(ProfileVisibility.ANONYMOUS).build()));
    ViewerContext stranger = new ViewerContext(UUID.randomUUID(), 999L, false);

    assertThat(service.isFieldVisible(USER_ID, ProfileField.PHONE, stranger)).isFalse();
    verifyNoInteractions(fieldVisibilityRepository);
  }

  @Test
  void publicFieldIsVisibleToAnyLoggedOrAnonymousStranger() {
    when(userProfileRepository.findById(USER_ID))
        .thenReturn(Optional.of(UserProfile.builder().userId(USER_ID).visibility(ProfileVisibility.PUBLIC).build()));
    when(fieldVisibilityRepository.existsByUserIdAndFieldAndAudience(USER_ID, ProfileField.PHONE, Audience.PUBLIC))
        .thenReturn(true);
    ViewerContext stranger = new ViewerContext(UUID.randomUUID(), 999L, false);

    assertThat(service.isFieldVisible(USER_ID, ProfileField.PHONE, stranger)).isTrue();
  }

  @Test
  void friendsOnlyFieldIsNeverVisibleYetBecauseFriendshipIsNotImplemented() {
    when(userProfileRepository.findById(USER_ID))
        .thenReturn(Optional.of(UserProfile.builder().userId(USER_ID).visibility(ProfileVisibility.FRIENDS).build()));
    when(fieldVisibilityRepository.existsByUserIdAndFieldAndAudience(USER_ID, ProfileField.PHONE, Audience.PUBLIC))
        .thenReturn(false);
    ViewerContext someoneElse = new ViewerContext(UUID.randomUUID(), 999L, false);

    assertThat(service.isFieldVisible(USER_ID, ProfileField.PHONE, someoneElse)).isFalse();
  }
}
