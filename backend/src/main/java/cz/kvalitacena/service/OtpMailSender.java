package cz.kvalitacena.service;

/** Odeslání OTP kódu schované za rozhraní — implementace se přepíná přes app.auth.otp.mail-enabled. */
public interface OtpMailSender {
  void sendOtpCode(String email, String code);
}
