package cz.kvalitacena.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Převádí doménové výjimky na jednotné {@link ProblemDetail} odpovědi bez únikových detailů.
 * Doplněno o {@link NotFoundException}/{@link UnauthorizedException}/{@link IllegalArgumentException}
 * kvůli {@code MediaController} — druhému (a zatím jedinému dalšímu) REST controlleru vedle
 * {@code AuthController}, zbytek API je GraphQL a tyhle výjimky tam řeší {@code GraphQlExceptionHandler}.
 */
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

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail handleNotFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ProblemDetail handleUnauthorized(UnauthorizedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
