package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Verze Podmínek užití/Zásad ochrany osobních údajů (docs/podminky-uziti.md,
 * docs/zasady-ochrany-osobnich-udaju.md) — prostý řetězec, ne cizí klíč na dokument, appka
 * žádnou tabulku s historií verzí nemá. Bump při každé věcné změně obou dokumentů (ne při
 * překlepu) — {@code OtpService.verifyOtp} touhle hodnotou označí souhlas nové registrace.
 */
@Component
@ConfigurationProperties(prefix = "app.legal")
@Data
public class LegalProperties {
  private String termsVersion;
}
