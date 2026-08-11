package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Kurzovní lístek ČNB pro přepočet zobrazovací měny (docs/lokalizace.md, "Kurzovní lístek a
 * zobrazovací měna"). Vlastní datový zdroj s plánovanou úlohou, ne odkaz ven jako
 * {@code app.external.*} — proto samostatná sekce {@code app.fx}, ne pod {@code external}.
 *
 * <p>{@code trackedCurrencies} (co se stahuje z ČNB) a {@code displayCurrencies} (co appka smí
 * nabídnout k zobrazení) jsou záměrně DVA seznamy: {@code displayCurrencies} navíc obsahuje CZK
 * (pivot, netahá se z ČNB) a USD je čistě referenční — {@code CurrencyResolver} ho nikdy nesmí
 * nabídnout jako měnu ZÁPISU ceny, {@code app.i18n.country-currency} o USD neví.
 */
@Component
@ConfigurationProperties(prefix = "app.fx")
@Data
public class FxProperties {
  private boolean enabled;
  private String baseUrl;
  private Duration timeout;
  private String cron;
  private String zone;
  private List<String> trackedCurrencies;
  private List<String> displayCurrencies;
  private Duration cacheTtl;
  private int maxBackfillYears;
}
