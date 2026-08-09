package cz.kvalitacena.exception;

/** Refresh token neexistuje, vypršel nebo byl revokovaný — klient se musí přihlásit znovu. */
public class RefreshTokenInvalidException extends AppException {
  public RefreshTokenInvalidException() {
    super(ErrorCode.SESSION_EXPIRED);
  }
}
