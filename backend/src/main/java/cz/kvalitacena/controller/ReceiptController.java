package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Nahrání účtenky a její obrázky pro vlastníka — REST ze stejného důvodu jako
 * {@link MediaController} (multipart GraphQL tu není). Metadata a párování jdou přes GraphQL
 * ({@link ReceiptGraphQlController}).
 */
@RestController
@RequestMapping("/api/receipts")
@RequiredArgsConstructor
public class ReceiptController {

  private final ReceiptService receiptService;
  private final ViewerContextResolver viewerContextResolver;

  public record UploadResponse(Long id) {
  }

  /**
   * {@code images} v pořadí shora dolů. {@code consentVersion} = verze textu souhlasu, kterou
   * uživatel odsouhlasil (docs/soukromi.md, „Účtenky"). {@code clientUploadId} = klíč
   * idempotence, mobil ho posílá stejný při každém opakování z fronty.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public UploadResponse upload(@RequestParam("images") List<MultipartFile> images,
      @RequestParam("consentVersion") Integer consentVersion,
      @RequestParam(value = "archiveConsent", defaultValue = "false") boolean archiveConsent,
      @RequestParam(value = "capturedAt", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime capturedAt,
      @RequestParam(value = "clientUploadId", required = false) UUID clientUploadId,
      Authentication authentication) {
    List<byte[]> bytes = images.stream().map(ReceiptController::readBytes).toList();
    Receipt receipt = receiptService.upload(viewerContextResolver.resolve(authentication).publicUid(), bytes,
        consentVersion, archiveConsent, capturedAt, clientUploadId);
    return new UploadResponse(receipt.getId());
  }

  /** Jen vlastník; cizí nebo smazaný obrázek je 404, nikdy 403 (existence se neprozrazuje). */
  @GetMapping("/{id}/images/{seq}")
  public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable int seq, Authentication authentication) {
    return receiptService.ownerImage(viewerContextResolver.resolve(authentication).publicUid(), id, seq)
        .map(bytes -> ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Nahraný soubor se nepodařilo přečíst", e);
    }
  }
}
