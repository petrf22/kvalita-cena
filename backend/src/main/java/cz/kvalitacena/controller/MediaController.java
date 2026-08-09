package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Media;
import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.repo.MediaRepository;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.MediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Upload a stahování binárního obsahu fotek — jediné místo mimo GraphQL vedle
 * {@code AuthController}. Multipart upload (graphql-multipart-request-spec) Spring for GraphQL
 * nepodporuje a streamování binárek přes GraphQL by bylo nepřirozené; metadata (typ
 * {@link Photo}) naopak jdou přes GraphQL jako zbytek katalogu, aby detail produktu/obchodu
 * zůstal jeden dotaz.
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

  private final MediaService mediaService;
  private final MediaRepository mediaRepository;
  private final MediaStorage mediaStorage;
  private final ViewerContextResolver viewerContextResolver;

  @PostMapping("/{recordType}/{recordId}")
  public Photo upload(@PathVariable RecordType recordType, @PathVariable Long recordId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "caption", required = false) String caption,
      Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    Media media = mediaService.upload(recordType, recordId, readBytes(file), caption, viewer.publicUid());
    return mediaService.toPhoto(media, viewer);
  }

  @GetMapping("/{id}")
  public ResponseEntity<byte[]> full(@PathVariable Long id, Authentication authentication) {
    return serve(id, MediaStorage.Variant.FULL, authentication);
  }

  @GetMapping("/{id}/thumb")
  public ResponseEntity<byte[]> thumbnail(@PathVariable Long id, Authentication authentication) {
    return serve(id, MediaStorage.Variant.THUMBNAIL, authentication);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
    mediaService.delete(id, viewerContextResolver.resolve(authentication).publicUid());
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<byte[]> serve(Long id, MediaStorage.Variant variant, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    Media media = mediaRepository.findById(id).orElse(null);
    // Skrytá fotka se tváří jako neexistující i pro cizí lidi — nikdy 403 (CLAUDE.md, stejné
    // pravidlo jako u neviditelných recenzí: NOT_FOUND, ne FORBIDDEN, aby existenci skrytého
    // záznamu nešlo zvenčí odvodit).
    boolean visible = media != null
        && (media.getHiddenAt() == null || media.getUploadedByUserId().equals(viewer.userId()));
    if (!visible) {
      return ResponseEntity.notFound().build();
    }
    byte[] bytes = mediaStorage.load(media.getStorageKey(), variant).orElse(null);
    if (bytes == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        // Obsah fotky pod daným id se nikdy nemění (jen její existence) — bezpečné cachovat
        // agresivně, i na veřejných CDN/v prohlížeči.
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .eTag("\"" + media.getId() + "-" + variant + "\"")
        .body(bytes);
  }

  private byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Nahraný soubor se nepodařilo přečíst", e);
    }
  }
}
