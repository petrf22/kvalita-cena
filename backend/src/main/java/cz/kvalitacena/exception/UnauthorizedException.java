package cz.kvalitacena.exception;

/** Akce vyžaduje přihlášení, request je anonymní (GraphQL) — viz GraphQlExceptionHandler. */
public class UnauthorizedException extends RuntimeException {
  public UnauthorizedException(String message) {
    super(message);
  }
}
