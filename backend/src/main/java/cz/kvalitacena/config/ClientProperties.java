package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nejnižší {@code versionCode} mobilní appky, který backend ještě obsluhuje
 * (docs/vydani.md, docs/datovy-model.md — vydáním APK zamrzne GraphQL kontrakt, starším
 * klientům v terénu by pozdější breaking změna schématu shodila dotaz beze slova vysvětlení).
 * {@code 0} (default) vypíná kontrolu — v etapě 1 ještě žádné APK není v terénu.
 */
@Component
@ConfigurationProperties(prefix = "app.client")
@Data
public class ClientProperties {
  private int minAndroidVersion;
}
