package cz.kvalitacena.service;

import java.util.Locale;

/**
 * Odeslání transakčních e-mailů (přihlašovací kód, změna přihlašovacího e-mailu) schované za
 * rozhraní — implementace se přepíná přes app.auth.otp.mail-enabled.
 */
public interface OtpMailSender {
  /**
   * @param locale Jazyk e-mailu — uložená preference existujícího účtu, jinak Accept-Language
   *               requestu (viz OtpService.requestOtp, docs/lokalizace.md).
   */
  void sendOtpCode(String email, String code, Locale locale);

  /** Kód pro potvrzení NOVÉ přihlašovací adresy (EmailChangeService.requestChange). */
  void sendEmailChangeCode(String email, String code, Locale locale);

  /**
   * Adresa je už u jiného účtu — místo kódu jde jen upozornění, ať se enumerace účtů nedá
   * odvodit z toho, že by se REST odpověď lišila (docs/soukromi.md).
   */
  void sendEmailChangeConflict(String email, Locale locale);

  /** Potvrzovací kód pro nevratný výmaz účtu (AccountService, docs/soukromi.md, "GDPR"). */
  void sendAccountDeleteCode(String email, String code, Locale locale);
}
