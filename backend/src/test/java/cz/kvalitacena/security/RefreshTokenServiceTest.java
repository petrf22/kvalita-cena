package cz.kvalitacena.security;

import cz.kvalitacena.config.RefreshTokenProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.entity.RefreshToken;
import cz.kvalitacena.db.repo.RefreshTokenRepository;
import cz.kvalitacena.exception.RefreshTokenInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pozastavený účet (docs/podminky-uziti.md, "Ukončení a vyloučení") nesmí obnovit session,
 * i kdyby refresh token sám byl ještě platný — setUserSuspended tokeny revokuje, tohle je
 * pojistka proti souběhu (ModerationService.setUserSuspended).
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  @Mock
  private RefreshTokenRepository refreshTokenRepository;

  private RefreshTokenProperties properties;
  private RefreshTokenService service;

  @BeforeEach
  void setUp() {
    properties = new RefreshTokenProperties();
    properties.setWebTtl(Duration.ofDays(30));
    properties.setAndroidTtl(Duration.ofDays(60));
    properties.setReuseGracePeriod(Duration.ofSeconds(30));
    service = new RefreshTokenService(refreshTokenRepository, properties);
  }

  private RefreshToken tokenFor(AppUser user) {
    return RefreshToken.builder()
        .id(1L)
        .familyUid(java.util.UUID.randomUUID())
        .user(user)
        .expiresAt(OffsetDateTime.now().plusDays(1))
        .build();
  }

  @Test
  void rotateRejectsSuspendedAccount() {
    AppUser suspended = AppUser.builder().id(5L).status(AppUserStatus.SUSPENDED).build();
    when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(tokenFor(suspended)));

    assertThatThrownBy(() -> service.rotate("rawtoken", ClientKind.WEB, "device"))
        .isInstanceOf(RefreshTokenInvalidException.class);
  }

  @Test
  void rotateAllowsActiveAccount() {
    AppUser active = AppUser.builder().id(5L).status(AppUserStatus.ACTIVE).build();
    when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(tokenFor(active)));
    when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RefreshTokenService.IssuedToken issued = service.rotate("rawtoken", ClientKind.WEB, "device");

    org.assertj.core.api.Assertions.assertThat(issued.rawToken()).isNotBlank();
  }
}
