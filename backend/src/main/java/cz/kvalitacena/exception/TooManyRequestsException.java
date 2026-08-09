package cz.kvalitacena.exception;

/** Vyčerpaný rate limit (cooldown, hodinový nebo denní strop) — viz OtpRateLimiter. */
public class TooManyRequestsException extends AppException {
  public TooManyRequestsException() {
    super(ErrorCode.TOO_MANY_REQUESTS);
  }
}
