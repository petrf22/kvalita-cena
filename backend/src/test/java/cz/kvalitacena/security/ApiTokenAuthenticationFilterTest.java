package cz.kvalitacena.security;

import cz.kvalitacena.db.entity.ApiToken;
import cz.kvalitacena.db.entity.ApiTokenScope;
import cz.kvalitacena.db.repo.ApiTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Strojový token dostane jen roli svého rozsahu — worker nesmí kurátorovat a naopak. */
class ApiTokenAuthenticationFilterTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void workerTokenGetsOnlyWorkerRole() throws Exception {
    assertThat(rolesFor(ApiTokenScope.RECEIPT_WORKER)).containsExactly("ROLE_RECEIPT_WORKER");
  }

  @Test
  void curatorTokenGetsOnlyCuratorRole() throws Exception {
    assertThat(rolesFor(ApiTokenScope.LABEL_CURATOR)).containsExactly("ROLE_LABEL_CURATOR");
  }

  private java.util.List<String> rolesFor(ApiTokenScope scope) throws Exception {
    ApiTokenRepository repository = mock(ApiTokenRepository.class);
    ApiToken token = mock(ApiToken.class);
    when(token.getId()).thenReturn(7L);
    when(token.getName()).thenReturn("test");
    when(token.getScope()).thenReturn(scope);
    when(repository.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.of(token));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + ApiTokenAuthenticationFilter.TOKEN_PREFIX + scope.name());
    new ApiTokenAuthenticationFilter(repository).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .map(GrantedAuthority::getAuthority).toList();
  }
}
