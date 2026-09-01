package cz.kvalitacena.security;

import cz.kvalitacena.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} — sdílí ho {@code AuthController}/{@code EmailChangeController}/
 * {@code AccountController}/{@code FeedbackGraphQlController}. Klíčový scénář je "spoofed
 * header": appka má za sebou přesně jednu důvěryhodnou proxy (Caddy), takže resolver musí brát
 * hodnotu ZPRAVA, ne první položku zleva, kterou si klient může sám dopsat.
 */
@ExtendWith(MockitoExtension.class)
class ClientIpResolverTest {

  @Mock
  private HttpServletRequest request;

  private ClientIpResolver resolver;
  private SecurityProperties securityProperties;

  @BeforeEach
  void setUp() {
    securityProperties = new SecurityProperties();
    resolver = new ClientIpResolver(securityProperties);
  }

  @Test
  void spoofedHeaderPrefixIsIgnored_takesValueAddedByTrustedProxy() {
    // Útočník si připojí "1.2.3.4" a doufá, že se vezme jako jeho IP — Caddy za tím připojí
    // skutečnou spojovací IP klienta.
    when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 198.51.100.7");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
  }

  @Test
  void missingHeaderFallsBackToRemoteAddr() {
    when(request.getHeader("X-Forwarded-For")).thenReturn(null);
    when(request.getRemoteAddr()).thenReturn("203.0.113.5");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void blankHeaderFallsBackToRemoteAddr() {
    when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
    when(request.getRemoteAddr()).thenReturn("203.0.113.5");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void multipleTrustedProxiesTakeConfiguredCountFromTheRight() {
    securityProperties.setTrustedProxyCount(2);
    resolver = new ClientIpResolver(securityProperties);

    when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 198.51.100.7, 10.0.0.1");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
  }

  @Test
  void fewerEntriesThanTrustedProxiesFallsBackToRemoteAddr() {
    securityProperties.setTrustedProxyCount(3);
    resolver = new ClientIpResolver(securityProperties);

    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");
    when(request.getRemoteAddr()).thenReturn("203.0.113.5");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void garbageValueFallsBackToRemoteAddr() {
    when(request.getHeader("X-Forwarded-For")).thenReturn("not-an-ip");
    when(request.getRemoteAddr()).thenReturn("203.0.113.5");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void ipv6ValueIsAccepted() {
    when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 2001:db8::1");

    assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
  }
}
