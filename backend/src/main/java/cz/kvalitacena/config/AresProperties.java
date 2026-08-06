package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** ARES (veřejný rejstřík ekonomických subjektů ČR) — ne ODbL data, jen předvyplnění formuláře obchodu podle IČO. */
@Component
@ConfigurationProperties(prefix = "app.external.ares")
@Data
public class AresProperties {
  private String baseUrl;
  private Duration timeout;
}
