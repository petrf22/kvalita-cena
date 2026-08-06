package cz.kvalitacena.config;

import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Převádí doménové výjimky na GraphQL chyby se správnou klasifikací (obdoba GlobalExceptionHandler pro REST). */
@Component
public class GraphQlExceptionHandler extends DataFetcherExceptionResolverAdapter {

  @Override
  protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
    if (ex instanceof NotFoundException) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.NOT_FOUND)
          .message(ex.getMessage())
          .build();
    }
    if (ex instanceof IllegalArgumentException) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.BAD_REQUEST)
          .message(ex.getMessage())
          .build();
    }
    if (ex instanceof UnauthorizedException) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.UNAUTHORIZED)
          .message(ex.getMessage())
          .build();
    }
    if (ex instanceof TooManyRequestsException) {
      // ErrorType nemá vlastní kategorii pro 429 — BAD_REQUEST je nejbližší (klientská chyba,
      // ne serverová), rozlišitelnost pro klienta dodává extensions.code níž.
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.BAD_REQUEST)
          .message(ex.getMessage())
          .extensions(Map.of("code", "TOO_MANY_REQUESTS"))
          .build();
    }
    if (ex instanceof DuplicateException duplicate) {
      GraphqlErrorBuilder<?> builder = GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.BAD_REQUEST)
          .message(ex.getMessage());
      if (duplicate.getExistingId() != null) {
        builder.extensions(Map.of("code", "DUPLICATE", "existingId", duplicate.getExistingId()));
      } else {
        builder.extensions(Map.of("code", "DUPLICATE"));
      }
      return builder.build();
    }
    return null; // necháme na výchozím zpracování (INTERNAL_ERROR, bez úniku detailů)
  }
}
