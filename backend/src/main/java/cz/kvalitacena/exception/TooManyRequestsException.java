package cz.kvalitacena.exception;

/** Vyčerpaný rate limit (cooldown, hodinový nebo denní strop) — viz OtpRateLimiter. */
public class TooManyRequestsException extends RuntimeException {
  public TooManyRequestsException() {
    super("Příliš mnoho požadavků, zkus to prosím později");
  }
}
