package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.security.ClientIpResolver;
import cz.kvalitacena.security.EmailChangeConfirmRequest;
import cz.kvalitacena.security.EmailChangeRequestRequest;
import cz.kvalitacena.security.EmailChangeRequestResponse;
import cz.kvalitacena.service.EmailChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Změna přihlašovacího e-mailu — VLASTNÍ REST tok vedle {@link AuthController} (docs/soukromi.md,
 * "Profil uživatele a viditelnost"), ne GraphQL mutace: kód jde přes stejný OTP mechanismus jako
 * login, jen na NOVOU adresu. Autorizace je predikát nad {@link Authentication}, ne blokování na
 * úrovni URL (viz {@code SecurityConfig}, {@code /api/auth/**} je {@code permitAll} pro všechny —
 * stejný princip jako u {@code MediaController}).
 */
@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailChangeController {

  private final EmailChangeService emailChangeService;
  private final AppUserRepository appUserRepository;
  private final ClientIpResolver clientIpResolver;

  @PostMapping("/change/request")
  public EmailChangeRequestResponse requestChange(@Valid @RequestBody EmailChangeRequestRequest request,
      Authentication authentication, HttpServletRequest servletRequest) {
    AppUser user = requireCurrentUser(authentication);
    ClientKind clientKind = resolveClientKind(servletRequest);
    EmailChangeService.RequestResult result = emailChangeService.requestChange(user, request.email(), clientKind,
        clientIpResolver.resolve(servletRequest));
    return new EmailChangeRequestResponse(result.challengeUid(), result.expiresInSec(), result.resendAfterSec());
  }

  @PostMapping("/change/confirm")
  public ResponseEntity<Void> confirmChange(@Valid @RequestBody EmailChangeConfirmRequest request,
      Authentication authentication) {
    AppUser user = requireCurrentUser(authentication);
    emailChangeService.confirmChange(user, request.challengeUid(), request.code(), request.email());
    return ResponseEntity.noContent().build();
  }

  private AppUser requireCurrentUser(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      throw new UnauthorizedException(ErrorCode.EMAIL_CHANGE_REQUIRES_LOGIN);
    }
    return appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
  }

  private ClientKind resolveClientKind(HttpServletRequest request) {
    String header = request.getHeader("X-Client-Kind");
    if ("ANDROID".equalsIgnoreCase(header)) return ClientKind.ANDROID;
    return ClientKind.WEB;
  }
}
