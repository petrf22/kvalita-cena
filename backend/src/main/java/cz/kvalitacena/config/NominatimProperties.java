package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenStreetMap Nominatim — jen geokódování adresy na souřadnice, žádný import POI (viz plán
 * projektu). Usage policy Nominatimu (https://operations.osmfoundation.org/policies/nominatim/)
 * vyžaduje identifikovatelný User-Agent a nejvýš 1 dotaz/s — GeocodingService obojí dodržuje.
 * Volá se VÝHRADNĚ ze serveru, nikdy z mobilu/prohlížeče (docs/soukromi.md — jinak by šla na
 * Nominatim přímo IP uživatele).
 */
@Component
@ConfigurationProperties(prefix = "app.external.nominatim")
@Data
public class NominatimProperties {
  private String baseUrl;
  private String userAgent;
  private Duration timeout;
  private Duration cacheTtl;
  private String attribution;
}
