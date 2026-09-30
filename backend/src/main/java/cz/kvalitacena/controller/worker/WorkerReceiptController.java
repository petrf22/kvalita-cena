package cz.kvalitacena.controller.worker;

import cz.kvalitacena.service.ReceiptWorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pull worker účtenek (docs/ai.md, „Účtenky") — přístup jen se strojovým tokenem
 * ({@code ROLE_RECEIPT_WORKER}, SecurityConfig). Worker nic nerozhoduje: vrací vytěžený text,
 * server z něj udělá návrh k potvrzení uživatelem.
 */
@RestController
@RequestMapping("/api/worker/receipts")
@RequiredArgsConstructor
public class WorkerReceiptController {

  private final ReceiptWorkerService workerService;

  public record FailRequest(String error) {
  }

  @PostMapping("/lease")
  public List<WorkerReceiptJob> lease(@RequestParam(value = "limit", required = false) Integer limit) {
    return workerService.lease(limit);
  }

  @GetMapping("/{id}/images/{seq}")
  public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable int seq) {
    return workerService.image(id, seq)
        .map(bytes -> ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/result")
  public ResponseEntity<Void> result(@PathVariable Long id, @RequestBody WorkerReceiptResult result) {
    workerService.submitResult(id, result);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/fail")
  public ResponseEntity<Void> fail(@PathVariable Long id, @RequestBody(required = false) FailRequest request) {
    workerService.fail(id, request == null ? null : request.error());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/archive-candidates")
  public List<WorkerArchiveCandidate> archiveCandidates(@RequestParam(value = "limit", required = false) Integer limit) {
    return workerService.archiveCandidates(limit);
  }

  @PostMapping("/{id}/archived")
  public ResponseEntity<Void> archived(@PathVariable Long id) {
    workerService.markArchived(id);
    return ResponseEntity.noContent().build();
  }
}
