package cz.kvalitacena.exception;

/**
 * Nahrazuje {@code IllegalArgumentException} ve service vrstvě pro chyby, které jsou vždy
 * způsobené vstupem od klienta (chybějící pole, špatný tvar, mimo rozsah) — na rozdíl od
 * technických {@code IllegalArgumentException}/{@code IllegalStateException}, které se
 * k uživateli nikdy nedostanou (viz whitelist v {@code HardcodedTextTest}).
 */
public class ValidationException extends AppException {
  public ValidationException(ErrorCode code, Object... args) {
    super(code, args);
  }

  public ValidationException(ErrorCode code, Throwable cause, Object... args) {
    super(code, cause, args);
  }
}
