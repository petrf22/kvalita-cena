package cz.kvalitacena.controller.curator;

import cz.kvalitacena.service.LabelCurationService;
import cz.kvalitacena.service.LabelCurationService.LabelMappingRequest;
import cz.kvalitacena.service.LabelCurationService.LabelMappingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kurátor označení z účtenek (tools/uctenky/curate.py) — jen strojový token s rozsahem
 * {@code LABEL_CURATOR} (SecurityConfig). Odpovědi jsou pro provozovatelův nástroj, ne pro
 * klienta appky, takže chyby nesou prostou zprávu místo lokalizovaného kódu.
 */
@RestController
@RequestMapping("/api/curator")
@RequiredArgsConstructor
public class CuratorLabelController {

  private final LabelCurationService curationService;

  @GetMapping("/labels/unmatched")
  public List<LabelCurationService.UnmatchedLabel> unmatched(
      @RequestParam(value = "chainId", required = false) Long chainId,
      @RequestParam(value = "limit", required = false) Integer limit) {
    return curationService.unmatched(chainId, limit);
  }

  @GetMapping("/products/search")
  public List<LabelCurationService.ProductCandidate> searchProducts(@RequestParam("q") String query,
      @RequestParam(value = "storeId", required = false) Long storeId,
      @RequestParam(value = "limit", required = false) Integer limit) {
    return curationService.searchProducts(query, storeId, limit);
  }

  @PostMapping("/labels")
  public ResponseEntity<LabelMappingResult> map(@RequestBody LabelMappingRequest request) {
    LabelMappingResult result = curationService.map(request);
    HttpStatus status = switch (result.outcome()) {
      case CREATED -> HttpStatus.CREATED;
      case UPDATED, UNCHANGED -> HttpStatus.OK;
      case CONFLICT -> HttpStatus.CONFLICT;
      case INVALID -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(result);
  }
}
