package cz.kvalitacena.exception;

import cz.kvalitacena.service.Messages;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Převádí doménové výjimky na jednotné {@link ProblemDetail} odpovědi bez únikových detailů —
 * pro {@code MediaController}/{@code AuthController}, druhé (a zatím jediné další) REST
 * controllery vedle GraphQL, které řeší {@code GraphQlExceptionHandler}.
 *
 * <p>Kontrakt je stejný jako u GraphQL: {@code properties.code} + {@code properties.params}
 * jsou strojově čitelné, {@code detail} je jen lokalizovaný fallback (docs/lokalizace.md).
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  private final Messages messages;

  @ExceptionHandler(AppException.class)
  public ProblemDetail handleAppException(AppException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        statusFor(e), messages.get(e.getCode().getMessageKey(), (Object[]) e.getArgs()));
    problem.setProperty("code", e.getCode().name());
    if (e.getArgs().length > 0) {
      problem.setProperty("params", List.of(e.getArgs()));
    }
    if (e instanceof DuplicateException duplicate && duplicate.getExistingId() != null) {
      problem.setProperty("existingId", duplicate.getExistingId());
    }
    return problem;
  }

  /**
   * {@code @Valid} na REST vstupech (např. {@code OtpRequestRequest}) dřív propadalo do
   * defaultní Spring odpovědi anglicky — chybělo tu vůbec zachytit ji. Zprávy jednotlivých
   * polí už jsou lokalizované přes {@code LocalValidatorFactoryBean} (viz I18nConfig), tady
   * se jen sesbírají do jednotné obálky.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, messages.get(ErrorCode.VALIDATION_FAILED.getMessageKey()));
    problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
    problem.setProperty("fieldErrors", e.getBindingResult().getFieldErrors().stream()
        .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a)));
    return problem;
  }

  /**
   * Request nad {@code spring.servlet.multipart.max-request-size} odmítne už multipart vrstva,
   * dřív než controller — bez tohohle handleru by klient dostal holé 413 bez kódu a nevěděl,
   * co s tím. U účtenky je to součet všech dílů, takže rada zní „méně dílů".
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request) {
    ErrorCode code = request.getRequestURI().startsWith("/api/receipts")
        ? ErrorCode.RECEIPT_UPLOAD_TOO_LARGE : ErrorCode.PHOTO_TOO_LARGE;
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONTENT_TOO_LARGE, messages.get(code.getMessageKey()));
    problem.setProperty("code", code.name());
    return problem;
  }

  private HttpStatus statusFor(AppException e) {
    if (e instanceof NotFoundException) {
      return HttpStatus.NOT_FOUND;
    }
    if (e instanceof UnauthorizedException) {
      return HttpStatus.UNAUTHORIZED;
    }
    if (e instanceof RefreshTokenInvalidException) {
      return HttpStatus.UNAUTHORIZED;
    }
    if (e instanceof TooManyRequestsException) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    return HttpStatus.BAD_REQUEST;
  }
}
