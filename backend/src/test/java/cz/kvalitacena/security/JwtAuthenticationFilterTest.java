package cz.kvalitacena.security;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bez Bearer hlavičky/s neplatným tokenem request pokračuje anonymně (docs/reputace.md, T0).
 * Pozastavený účet (docs/podminky-uziti.md, "Ukončení a vyloučení") se přestane autentizovat
 * nejpozději do 60 s (TTL cache), i když je token_version stále platný — status != ACTIVE
 * blokuje samostatně, ne jen přes token_version.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private JwtService jwtService;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private FilterChain filterChain;

  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new JwtAuthenticationFilter(jwtService, appUserRepository);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest requestWithBearerToken(int tokenVersion) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer sometoken");
    when(jwtService.parse("sometoken")).thenReturn(Optional.of(new JwtService.ParsedAccessToken(PUBLIC_UID, tokenVersion)));
    return request;
  }

  @Test
  void noAuthorizationHeaderLeavesRequestAnonymous() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void activeUserGetsRoleUser() throws Exception {
    MockHttpServletRequest request = requestWithBearerToken(0);
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(
        AppUser.builder().tokenVersion(0).status(AppUserStatus.ACTIVE).moderator(false).build()));

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
  }

  @Test
  void moderatorGetsBothRoles() throws Exception {
    MockHttpServletRequest request = requestWithBearerToken(0);
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(
        AppUser.builder().tokenVersion(0).status(AppUserStatus.ACTIVE).moderator(true).build()));

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_USER", "ROLE_MODERATOR");
  }

  @Test
  void suspendedAccountIsNotAuthenticatedEvenWithMatchingTokenVersion() throws Exception {
    MockHttpServletRequest request = requestWithBearerToken(0);
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(
        AppUser.builder().tokenVersion(0).status(AppUserStatus.SUSPENDED).moderator(false).build()));

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void staleTokenVersionIsNotAuthenticated() throws Exception {
    MockHttpServletRequest request = requestWithBearerToken(0);
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(
        AppUser.builder().tokenVersion(1).status(AppUserStatus.ACTIVE).moderator(false).build()));

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
