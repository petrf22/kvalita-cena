package cz.kvalitacena.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Převádí doménové výjimky na jednotné {@link ProblemDetail} odpovědi bez únikových detailů. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(InvalidChallengeException.class)
  public ProblemDetail handleInvalidChallenge(InvalidChallengeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ProblemDetail handleTooManyRequests(TooManyRequestsException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  @ExceptionHandler(RefreshTokenInvalidException.class)
  public ProblemDetail handleRefreshTokenInvalid(RefreshTokenInvalidException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }
}
