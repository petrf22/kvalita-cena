package cz.kvalitacena.exception;

import lombok.Getter;

/**
 * Společný předek doménových výjimek, které se mohou dostat až ke klientovi (GraphQL/REST).
 * Nese strojový {@link ErrorCode} a datové parametry pro lokalizovanou zprávu — {@code
 * getMessage()} vrací jen {@code code.name()}, NIKDY text pro člověka: ten se skládá až
 * v {@code GraphQlExceptionHandler}/{@code GlobalExceptionHandler} podle {@code
 * Accept-Language} (viz {@code service/Messages}).
 *
 * <p>Kontrakt chyby je {@code extensions.code} + {@code extensions.params} — {@code message}
 * je jen lokalizovaný fallback pro klienta, který kód ještě nezná. Do {@code args} proto smí
 * jen DATOVÁ hodnota (číslo, symbolické jméno enumu, název), nikdy přeložený kus věty — jinak
 * viz {@link cz.kvalitacena.service.MediaService} historii "Fotku smí … jen autor", kterou
 * nešlo přeložit do jazyků s jiným slovosledem/vazbou slovesa.
 */
@Getter
public abstract class AppException extends RuntimeException {

  private final ErrorCode code;
  private final transient Object[] args;

  protected AppException(ErrorCode code, Object... args) {
    super(code.name());
    this.code = code;
    this.args = args;
  }

  /** S příčinou (parsovací/IO chyba) — zachová se ve stacktrace pro logy, i když message zůstává jen kód. */
  protected AppException(ErrorCode code, Throwable cause, Object... args) {
    super(code.name(), cause);
    this.code = code;
    this.args = args;
  }
}
