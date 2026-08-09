package cz.kvalitacena.service;

import java.util.Locale;

/** Odeslání OTP kódu schované za rozhraní — implementace se přepíná přes app.auth.otp.mail-enabled. */
public interface OtpMailSender {
  /**
   * @param locale Jazyk e-mailu — uložená preference existujícího účtu, jinak Accept-Language
   *               requestu (viz OtpService.requestOtp, docs/lokalizace.md).
   */
  void sendOtpCode(String email, String code, Locale locale);
}
