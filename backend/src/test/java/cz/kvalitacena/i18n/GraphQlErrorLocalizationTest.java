package cz.kvalitacena.i18n;

import cz.kvalitacena.config.GraphQlExceptionHandler;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.service.TestMessages;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ověřuje kontrakt chyby (docs/lokalizace.md) na úrovni {@link GraphQlExceptionHandler}: pro
 * dané {@code Accept-Language} (přes {@link org.springframework.context.i18n.LocaleContextHolder},
 * stejně jako ho nastaví {@code UserAwareLocaleResolver} za běhu appky) přijde `message`
 * lokalizovaná do daného jazyka a `extensions.code` zůstává stejný strojový řetězec bez ohledu
 * na jazyk. Bez zvedání celého Spring kontextu (v projektu žádný test nedělá, viz CI komentář
 * v build.gradle) — {@link TestMessages} dá handleru reálný `MessageSource` nad skutečnými bundly.
 */
class GraphQlErrorLocalizationTest {

  private final GraphQlExceptionHandler handler = new GraphQlExceptionHandler(TestMessages.instance());
  private final DataFetchingEnvironment env =
      Mockito.mock(DataFetchingEnvironment.class, Mockito.RETURNS_DEEP_STUBS);

  @AfterEach
  void resetLocale() {
    org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void messageIsLocalizedButCodeStaysMachineReadable() {
    org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag("pl"));
    GraphQLError polish = resolve();

    org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
    GraphQLError czech = resolve();

    assertThat(polish.getExtensions().get("code")).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(czech.getExtensions().get("code")).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(polish.getMessage()).isNotEqualTo(czech.getMessage());
    assertThat(polish.getMessage()).contains("nie istnieje");
    assertThat(czech.getMessage()).contains("neexistuje");
  }

  @Test
  void unknownAcceptLanguageFallsBackToCzech() {
    org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.GERMAN);
    GraphQLError error = resolve();

    // ResourceBundleMessageSource s fallbackToSystemLocale=false spadne rovnou na základní
    // (český) bundle, ne na systémový jazyk serveru — viz I18nConfig.
    assertThat(error.getMessage()).contains("neexistuje");
  }

  private GraphQLError resolve() {
    var errors = handler.resolveException(new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND), env).block();
    assertThat(errors).hasSize(1);
    return errors.get(0);
  }
}
