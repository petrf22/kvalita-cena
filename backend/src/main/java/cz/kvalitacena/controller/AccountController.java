package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.ClientKind;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.security.AccountDeleteConfirmRequest;
import cz.kvalitacena.security.AccountDeleteRequestResponse;
import cz.kvalitacena.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * GDPR export a výmaz účtu (docs/soukromi.md, "GDPR") — VLASTNÍ REST tok jako
 * {@link EmailChangeController}, ne GraphQL mutace. Autorizace je predikát nad
 * {@link Authentication}, ne blokování na úrovni URL (viz {@code SecurityConfig},
 * {@code /api/me/**} je {@code permitAll} stejně jako {@code /api/auth/**}) — stejný princip
 * jako u {@code EmailChangeController}/{@code MediaController}.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class AccountController {

  private final AccountService accountService;
  private final AppUserRepository appUserRepository;

  @GetMapping("/export")
  public AccountExportResponse export(Authentication authentication) {
    AppUser user = requireCurrentUser(authentication);
    return accountService.exportData(user);
  }

  @PostMapping("/delete/request")
  public AccountDeleteRequestResponse requestDelete(Authentication authentication, HttpServletRequest servletRequest) {
    AppUser user = requireCurrentUser(authentication);
    ClientKind clientKind = resolveClientKind(servletRequest);
    AccountService.RequestResult result = accountService.requestDelete(user, clientKind, resolveIp(servletRequest));
    return new AccountDeleteRequestResponse(result.challengeUid(), result.expiresInSec(), result.resendAfterSec());
  }

  @PostMapping("/delete/confirm")
  public ResponseEntity<Void> confirmDelete(@Valid @RequestBody AccountDeleteConfirmRequest request,
      Authentication authentication) {
    AppUser user = requireCurrentUser(authentication);
    accountService.confirmDelete(user, request.challengeUid(), request.code());
    return ResponseEntity.noContent().build();
  }

  private AppUser requireCurrentUser(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UUID publicUid)) {
      throw new UnauthorizedException(ErrorCode.ACCOUNT_DELETE_REQUIRES_LOGIN);
    }
    return appUserRepository.findByPublicUid(publicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));
  }

  private ClientKind resolveClientKind(HttpServletRequest request) {
    String header = request.getHeader("X-Client-Kind");
    if ("ANDROID".equalsIgnoreCase(header)) return ClientKind.ANDROID;
    return ClientKind.WEB;
  }

  private String resolveIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
