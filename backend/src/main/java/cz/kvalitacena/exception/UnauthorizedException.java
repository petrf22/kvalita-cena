package cz.kvalitacena.exception;

/** Akce vyžaduje přihlášení, request je anonymní (GraphQL) — viz GraphQlExceptionHandler. */
public class UnauthorizedException extends AppException {
  public UnauthorizedException(ErrorCode code, Object... args) {
    super(code, args);
  }
}
