package cz.kvalitacena.config;

import cz.kvalitacena.security.ClientVersionFilter;
import cz.kvalitacena.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final ClientVersionFilter clientVersionFilter;
  private final SecurityProperties securityProperties;

  /** Argon2id pro hash OTP kódů — potřebuje Bouncy Castle na classpath, viz build.gradle. */
  @Bean
  public PasswordEncoder codeEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable) // stateless Bearer auth, žádné session cookies
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authz -> authz
            .requestMatchers("/api/auth/**").permitAll()
            // Autorizaci (kdo smí nahrát/smazat, co je vidět skryté) řeší MediaService z
            // Authentication, kterou JwtAuthenticationFilter naplní i tady stejně jako u
            // GraphQL — stejný princip "autorizace jako predikát", ne blokování na URL.
            .requestMatchers("/api/media/**").permitAll()
            .requestMatchers("/actuator/health/**", "/actuator/info/**").permitAll()
            // GraphQL běží na jednom endpointu pro anonymní (T0) i přihlášené uživatele —
            // odstupňování přístupu (docs/reputace.md) řeší predikáty v resolverech/ViewerContext,
            // ne blokování na úrovni URL (viz docs/datovy-model.md, "Autorizace jako predikát").
            .requestMatchers("/graphql", "/graphiql/**").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(clientVersionFilter, JwtAuthenticationFilter.class);

    return http.build();
  }

  private UrlBasedCorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowCredentials(true);
    config.setAllowedOrigins(securityProperties.getAllowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
