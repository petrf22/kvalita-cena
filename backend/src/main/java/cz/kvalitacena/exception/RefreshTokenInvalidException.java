package cz.kvalitacena.exception;

/** Refresh token neexistuje, vypršel nebo byl revokovaný — klient se musí přihlásit znovu. */
public class RefreshTokenInvalidException extends RuntimeException {
  public RefreshTokenInvalidException() {
    super("Přihlášení vypršelo, přihlas se prosím znovu");
  }
}
