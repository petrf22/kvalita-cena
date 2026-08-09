package cz.kvalitacena.config;

import cz.kvalitacena.exception.AppException;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.service.Messages;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Převádí doménové výjimky ({@link AppException} a potomky) na GraphQL chyby se správnou
 * klasifikací (obdoba {@link GlobalExceptionHandler} pro REST). Kontrakt chyby je
 * {@code extensions.code} + {@code extensions.params} — {@code message} je jen lokalizovaný
 * fallback podle {@code Accept-Language} (docs/lokalizace.md), klient primárně hledá vlastní
 * překlad podle {@code code}.
 */
@Component
@RequiredArgsConstructor
public class GraphQlExceptionHandler extends DataFetcherExceptionResolverAdapter {

  private final Messages messages;

  @Override
  protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
    if (!(ex instanceof AppException app)) {
      return null; // necháme na výchozím zpracování (INTERNAL_ERROR, bez úniku detailů)
    }

    Map<String, Object> extensions = new LinkedHashMap<>();
    extensions.put("code", app.getCode().name());
    if (app.getArgs().length > 0) {
      extensions.put("params", List.of(app.getArgs()));
    }
    if (app instanceof DuplicateException duplicate && duplicate.getExistingId() != null) {
      extensions.put("existingId", duplicate.getExistingId());
    }

    return GraphqlErrorBuilder.newError(env)
        .errorType(errorTypeFor(app))
        .message(messages.get(app.getCode().getMessageKey(), (Object[]) app.getArgs()))
        .extensions(extensions)
        .build();
  }

  private ErrorType errorTypeFor(AppException app) {
    if (app instanceof NotFoundException) {
      return ErrorType.NOT_FOUND;
    }
    if (app instanceof UnauthorizedException) {
      return ErrorType.UNAUTHORIZED;
    }
    // TooManyRequestsException i InvalidChallengeException nemají v ErrorType vlastní kategorii
    // pro 429 — BAD_REQUEST je nejbližší (klientská chyba, ne serverová), rozlišitelnost pro
    // klienta dodává extensions.code výš.
    return ErrorType.BAD_REQUEST;
  }
}
