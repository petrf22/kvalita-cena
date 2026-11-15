package cz.kvalitacena.config;

import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class GraphQlConfig {

  /**
   * Strop hloubky dotazu. {@code /graphql} je otevřený i anonymům a schéma má cyklické vazby
   * (zboží → cena → obchod → cena → zboží …), takže bez stropu jde jedním dotazem vyžádat
   * libovolně hluboký strom. Vypnutá introspekce v produkci to neřeší, schéma je ve veřejném
   * repozitáři. Dotaz nad limit skončí chybou ještě před spuštěním prvního resolveru.
   */
  @Bean
  public Instrumentation maxQueryDepthInstrumentation(
      @Value("${app.graphql.max-query-depth}") int maxDepth) {
    return new MaxQueryDepthInstrumentation(maxDepth);
  }

  @Bean
  public RuntimeWiringConfigurer runtimeWiringConfigurer() {
    return wiringBuilder -> wiringBuilder
        .scalar(GraphQlScalars.longScalar())
        .scalar(GraphQlScalars.bigDecimalScalar())
        .scalar(GraphQlScalars.dateTimeScalar())
        .scalar(GraphQlScalars.dateScalar());
  }
}
