package cz.kvalitacena.controller;

import cz.kvalitacena.config.RefreshTokenProperties;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.exception.RefreshTokenInvalidException;
import cz.kvalitacena.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Passwordless auth (e-mail → OTP kód → token) — jediné REST endpointy v aplikaci, zbytek API
 * je GraphQL. Refresh token webového klienta jde VÝHRADNĚ jako httpOnly
 * cookie, nikdy do těla odpovědi ani do JS paměti — Android ho dostává v těle a ukládá do
 * EncryptedSharedPreferences (docs/soukromi.md).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String REFRESH_COOKIE_NAME = "refresh_token";
  private static final String REFRESH_COOKIE_PATH = "/api/auth";

  private final OtpService otpService;
  private final RefreshTokenService refreshTokenService;
  private final RefreshTokenProperties refreshTokenProperties;
  private final JwtService jwtService;
  private final ClientIpResolver clientIpResolver;

  // V produkci nastavit na true (přes proměnnou prostředí) — lokální dev běží přes HTTP.
  @Value("${app.security.cookie-secure:false}")
  private boolean cookieSecure;

  @PostMapping("/otp/request")
  public OtpRequestResponse requestOtp(@Valid @RequestBody OtpRequestRequest request,
      HttpServletRequest servletRequest) {
    ClientKind clientKind = resolveClientKind(servletRequest);
    OtpService.OtpRequestResult result = otpService.requestOtp(
        request.email(), clientKind, clientIpResolver.resolve(servletRequest));
    return new OtpRequestResponse(result.challengeUid(), result.expiresInSec(), result.resendAfterSec());
  }

  @PostMapping("/otp/verify")
  public ResponseEntity<TokenResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request,
      HttpServletRequest servletRequest) {
    ClientKind clientKind = resolveClientKind(servletRequest);
    OtpService.AuthResult result = otpService.verifyOtp(request.challengeUid(), request.code(),
        request.email(), request.termsAccepted(), clientKind, resolveDevice(servletRequest));

    return respondWithTokens(result.accessToken(), result.refreshToken(), result.newUser(), clientKind);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @RequestBody(required = false) RefreshRequest request,
      HttpServletRequest servletRequest) {
    ClientKind clientKind = resolveClientKind(servletRequest);
    String rawToken = clientKind == ClientKind.WEB
        ? readCookie(servletRequest).orElseThrow(RefreshTokenInvalidException::new)
        : Optional.ofNullable(request).map(RefreshRequest::refreshToken)
            .orElseThrow(RefreshTokenInvalidException::new);

    RefreshTokenService.IssuedToken issued =
        refreshTokenService.rotate(rawToken, clientKind, resolveDevice(servletRequest));
    String accessToken = jwtService.issueAccessToken(issued.entity().getUser());

    return respondWithTokens(accessToken, issued.rawToken(), false, clientKind);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request,
      HttpServletRequest servletRequest) {
    ClientKind clientKind = resolveClientKind(servletRequest);
    String rawToken = clientKind == ClientKind.WEB
        ? readCookie(servletRequest).orElse(null)
        : Optional.ofNullable(request).map(RefreshRequest::refreshToken).orElse(null);

    if (rawToken != null) {
      refreshTokenService.revokeFamilyByRawToken(rawToken);
    }

    ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
    if (clientKind == ClientKind.WEB) {
      builder.header(HttpHeaders.SET_COOKIE, expiredCookie().toString());
    }
    return builder.build();
  }

  private ResponseEntity<TokenResponse> respondWithTokens(String accessToken, String refreshToken,
      boolean newUser, ClientKind clientKind) {
    if (clientKind == ClientKind.WEB) {
      ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
          .httpOnly(true)
          .secure(cookieSecure)
          .sameSite("Strict")
          .path(REFRESH_COOKIE_PATH)
          .maxAge(refreshTokenProperties.getWebTtl())
          .build();
      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, cookie.toString())
          .body(new TokenResponse(accessToken, null, newUser));
    }
    return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken, newUser));
  }

  private ResponseCookie expiredCookie() {
    return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite("Strict")
        .path(REFRESH_COOKIE_PATH)
        .maxAge(0)
        .build();
  }

  private Optional<String> readCookie(HttpServletRequest request) {
    if (request.getCookies() == null) return Optional.empty();
    return java.util.Arrays.stream(request.getCookies())
        .filter(c -> REFRESH_COOKIE_NAME.equals(c.getName()))
        .map(jakarta.servlet.http.Cookie::getValue)
        .findFirst();
  }

  private ClientKind resolveClientKind(HttpServletRequest request) {
    String header = request.getHeader("X-Client-Kind");
    if ("ANDROID".equalsIgnoreCase(header)) return ClientKind.ANDROID;
    return ClientKind.WEB;
  }

  private String resolveDevice(HttpServletRequest request) {
    String userAgent = request.getHeader("User-Agent");
    return userAgent == null ? "unknown" : userAgent.length() > 60 ? userAgent.substring(0, 60) : userAgent;
  }
}
