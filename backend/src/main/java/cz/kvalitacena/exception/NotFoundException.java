package cz.kvalitacena.exception;

/** Entita podle zadaného id neexistuje (GraphQL) — viz GraphQlExceptionHandler. */
public class NotFoundException extends AppException {
  public NotFoundException(ErrorCode code, Object... args) {
    super(code, args);
  }
}
