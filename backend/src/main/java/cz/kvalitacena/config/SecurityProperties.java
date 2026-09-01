package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CORS origins appky ({@link SecurityConfig}) — v dev natvrdo localhost Angular dev serveru,
 * v produkci {@code https://kvalitacena.cz} (docs/vydani.md). Mobil CORS nepodléhá (nativní
 * klient, ne prohlížeč), proto tu žádná adresa pro appku není.
 */
@Component
@ConfigurationProperties(prefix = "app.security")
@Data
public class SecurityProperties {
  private List<String> allowedOrigins = List.of("http://localhost:4200", "http://127.0.0.1:4200");

  /**
   * Kolik důvěryhodných reverzních proxy appka v produkci má PŘED backendem (dnes jen Caddy,
   * viz {@code frontend/Caddyfile}) — {@link cz.kvalitacena.security.ClientIpResolver} podle
   * toho čte {@code X-Forwarded-For} zprava, ne první položku zleva (tu si mohl klient sám
   * přidat). Zvednout jen při přidání dalšího reverzního proxy PŘED Caddy.
   */
  private int trustedProxyCount = 1;
}
