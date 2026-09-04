package cz.kvalitacena.exception;

/** Vyčerpaný rate limit (cooldown, hodinový nebo denní strop) — viz OtpRateLimiter. */
public class TooManyRequestsException extends AppException {
  public TooManyRequestsException() {
    super(ErrorCode.TOO_MANY_REQUESTS);
  }

  /** Strop, u kterého má uživatel vědět, čeho se týká — obecné "moc požadavků" by neporadilo. */
  public TooManyRequestsException(ErrorCode code) {
    super(code);
  }
}
