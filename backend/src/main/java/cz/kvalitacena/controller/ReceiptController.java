package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ReceiptImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nahrání vytěžené účtenky — třetí místo mimo GraphQL vedle {@code AuthController}
 * a {@code MediaController}.
 *
 * <p>Proč REST a ne mutace: nahrávat účtenky dnes umí jen dávkový nástroj
 * {@code tools/uctenky} spouštěný z příkazové řádky, ne appka. Jeden POST s JSON tělem je pro
 * skript přirozený, kdežto GraphQL mutace by znamenala generovat dotaz a řešit jeho tvar mimo
 * {@code graphql-codegen}. ČTENÍ a potvrzování účtenky naopak přes GraphQL jde
 * ({@code ReceiptGraphQlController}), aby si klienti nemuseli stavět druhý kontrakt.
 *
 * <p>Obrázek účtenky se ve fázi lokálního OCR neposílá, endpoint proto není multipart — na
 * server jde jen normalizovaný dokument bez patičky (docs/soukromi.md, „Účtenka").
 */
@RestController
@RequestMapping("/api/receipts")
@RequiredArgsConstructor
public class ReceiptController {

  private final ReceiptImportService receiptImportService;
  private final ViewerContextResolver viewerContextResolver;
  private final AppUserRepository appUserRepository;

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ReceiptImportResult upload(@RequestBody ReceiptDocument document,
      Authentication authentication) {
    // "Autorizace jako predikát" (SecurityConfig): cesta je permitAll, přihlášení kontroluje
    // služba nad Authentication, ne blokování URL.
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.isAnonymous()) {
      throw new ValidationException(ErrorCode.RECEIPT_REQUIRES_LOGIN);
    }
    AppUser uploader = appUserRepository.findById(viewer.userId())
        .orElseThrow(() -> new ValidationException(ErrorCode.RECEIPT_REQUIRES_LOGIN));
    return receiptImportService.importReceipt(document, uploader);
  }
}
