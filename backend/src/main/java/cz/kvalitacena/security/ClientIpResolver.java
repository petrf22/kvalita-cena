package cz.kvalitacena.security;

import cz.kvalitacena.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Klientská IP z {@code X-Forwarded-For} — sdílené místo pro {@code AuthController},
 * {@code EmailChangeController}, {@code AccountController} a {@code FeedbackGraphQlController},
 * dřív měl každý vlastní kopii, která brala PRVNÍ položku zleva.
 *
 * <p><b>To byla díra:</b> Caddy (viz {@code frontend/Caddyfile}) dělá {@code reverse_proxy} bez
 * {@code header_up X-Forwarded-For}, takže ve výchozím nastavení k hlavičce jen PŘIPOJUJE, ne
 * přepisuje — klient si mohl poslat {@code X-Forwarded-For: 1.2.3.4} a jakýkoli limiter na IP
 * (OTP {@link OtpRateLimiter}, feedback {@link FeedbackRateLimiter}) tím obejít. Pás k opravě
 * v Caddyfile ({@code header_up X-Forwarded-For {remote_host}}): tenhle resolver bere hodnotu
 * {@code N-tou zprava} podle {@code app.security.trusted-proxy-count} (výchozí 1 — appka má za
 * sebou jen Caddy), takže i kdyby Caddyfile opravu někdo omylem odstranil, přidané položky
 * útočníka zůstanou nalevo od té důvěryhodné a limiter dál funguje.
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

  // Syntaktická kontrola IPv4/IPv6 literálu, žádné DNS — InetAddress.getByName by na hostname
  // zkusilo skutečné resolvování, což tu nechceme (a nesmí to nikdy blokovat request).
  private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+:[0-9a-fA-F:]*$");

  private final SecurityProperties securityProperties;

  public String resolve(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor == null || forwardedFor.isBlank()) {
      return request.getRemoteAddr();
    }

    String[] parts = forwardedFor.split(",");
    int trustedProxyCount = Math.max(1, securityProperties.getTrustedProxyCount());
    int index = parts.length - trustedProxyCount;
    if (index < 0) {
      // Míň položek, než kolik proxy appka čeká — hlavička je podezřelá/zkrácená, radši
      // spadnout na skutečnou spojovací IP než věřit něčemu, co si klient sám vymyslel.
      return request.getRemoteAddr();
    }

    String candidate = parts[index].trim();
    return isValidIp(candidate) ? candidate : request.getRemoteAddr();
  }

  private boolean isValidIp(String value) {
    if (value.isEmpty() || value.length() > 45) return false; // max délka IPv6 zápisu
    return IPV4.matcher(value).matches() || IPV6.matcher(value).matches();
  }
}
