package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Národní banka Srbska — jediný zdroj kurzu pro RSD, ČNB ho na lístku nemá (ověřeno živě proti
 * api.cnb.cz, viz plán expanze). Na rozdíl od ČNB vyžaduje registraci a API klíč
 * ({@link #apiKey}) — bez něj appka o RSD kurz prostě přijde (stejná odolnost jako výpadek
 * ČNB), nespadne.
 */
@Component
@ConfigurationProperties(prefix = "app.external.nbs")
@Data
public class NbsProperties {
  private String baseUrl;
  private String apiKey;
  private Duration timeout;
}
