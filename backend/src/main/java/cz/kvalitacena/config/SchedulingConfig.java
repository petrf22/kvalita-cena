package cz.kvalitacena.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Zapíná plánované úlohy mimo testovací profil. Testy používají krátkodobé databáze z
 * Testcontainers, proto scheduler nesmí po ukončení testu přistupovat k již zastavenému
 * kontejneru.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
