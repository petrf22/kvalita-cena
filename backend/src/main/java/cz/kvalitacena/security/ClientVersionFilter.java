package cz.kvalitacena.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.kvalitacena.config.ClientProperties;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.service.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;

/**
 * Zahazuje požadavky ze starších mobilních klientů PŘED autorizací i před GraphQL/REST vrstvou
 * — vydáním APK zamrzne GraphQL kontrakt (docs/datovy-model.md), takže pozdější breaking změna
 * schématu by starému klientovi v terénu spadla na nesrozumitelnou parse chybu místo srozumitelné
 * "aktualizuj appku". Web hlavičku {@code X-Client-Version} neposílá, takže ho se tahle kontrola
 * netýká (stejný vzorec jako {@code X-Display-Currency} — hlavička, ne argument dotazu).
 *
 * <p>Odpověď je stejný {@link ProblemDetail} tvar jako {@code GlobalExceptionHandler}
 * ({@code properties.code} strojově čitelné, {@code detail} jen lokalizovaný fallback), ale psaný
 * ručně — v tomhle bodě filter chainu ještě neběží {@code DispatcherServlet}, takže
 * {@code @RestControllerAdvice} by request nezachytil.
 */
@Component
@RequiredArgsConstructor
public class ClientVersionFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Client-Version";

  private final ClientProperties clientProperties;
  private final Messages messages;
  private final LocaleResolver localeResolver;
  // Vlastní instance, ne injektovaný bean — appka (spring-boot-starter-webmvc) v tomhle
  // filter chainu, PŘED DispatcherServletem, negarantuje ObjectMapper bean stejného typu, jaký
  // by případně používaly HTTP message convertery (ověřeno ApplicationSmokeTest).
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    int minVersion = clientProperties.getMinAndroidVersion();
    String header = request.getHeader(HEADER);

    if (minVersion > 0 && header != null) {
      Integer clientVersion = parseVersion(header);
      if (clientVersion != null && clientVersion < minVersion) {
        respondUpgradeRequired(request, response);
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  private Integer parseVersion(String header) {
    try {
      return Integer.valueOf(header.trim());
    } catch (NumberFormatException e) {
      return null; // nerozpoznaná hlavička nesmí zablokovat request, jen se kontrola přeskočí
    }
  }

  private void respondUpgradeRequired(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var locale = localeResolver.resolveLocale(request);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UPGRADE_REQUIRED,
        messages.get(ErrorCode.CLIENT_VERSION_TOO_OLD.getMessageKey(), locale));
    problem.setProperty("code", ErrorCode.CLIENT_VERSION_TOO_OLD.name());

    response.setStatus(HttpStatus.UPGRADE_REQUIRED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Actuator zdraví/info musí projít i staré appce/monitoringu bez ohledu na hlavičku.
    String path = request.getRequestURI();
    return path.startsWith("/actuator/");
  }
}
