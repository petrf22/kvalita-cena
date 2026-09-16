package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenStreetMap Overpass API — nabídka provozoven v okolí při zakládání obchodu
 * (OverpassPoiService). Proč zrovna Overpass, a ne Nominatim, který už appka má: Nominatimova
 * usage policy vyhledávání POI podle kategorie ("systematic queries") výslovně zakazuje,
 * Overpass je na dotaz "co je v okruhu R" určený. Volá se VÝHRADNĚ ze serveru, nikdy z mobilu
 * (docs/soukromi.md — stejný důvod jako u Nominatimu).
 *
 * <p>{@code maxRadiusKm} a {@code maxResults} nejsou jen ochrana kvóty cizí služby, ale i
 * hranice proti "substantial part" cizí ODbL databáze (docs/datovy-model.md) — nabídka je vždy
 * hrst záznamů kolem jednoho bodu, ze které si člověk jeden vybere, ne výřez databáze.
 *
 * <p>{@code enabled: false} je kill switch bez redeploye — dotaz pak rovnou vrátí
 * {@code UNAVAILABLE}.
 */
@Component
@ConfigurationProperties(prefix = "app.external.overpass")
@Data
public class OverpassProperties {
  private boolean enabled = true;
  private String baseUrl;
  private String userAgent;
  /** Timeout HTTP spojení/čtení. Overpass je pomalejší než Nominatim, proto delší. */
  private Duration timeout;
  /** Jde do {@code [timeout:N]} v samotném dotazu — server si smí dotaz zabít dřív než my. */
  private Duration queryTimeout;
  private Duration cacheTtl;
  private double maxRadiusKm;
  private int maxResults;
}
