package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ProductOverlayService;
import cz.kvalitacena.service.ReceiptConfirmService;
import cz.kvalitacena.service.StoreOverlayService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Čtení a potvrzování vytěžených účtenek. Nahrání samotné jde přes REST
 * ({@code ReceiptController}) — dokument posílá dávkový nástroj z příkazové řádky, ne appka.
 *
 * <p>Účtenka je vidět JEN svému majiteli: je to přehled cizího nákupu, tedy citlivější
 * kategorie dat než cokoli, co appka dosud ukazuje (docs/soukromi.md, „Účtenka"). Cizí účtenka
 * se proto tváří jako neexistující, nikdy jako zakázaná — stejné pravidlo jako u skrytých
 * fotek a recenzí.
 */
@Controller
@RequiredArgsConstructor
public class ReceiptGraphQlController {

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository receiptLineRepository;
  private final StoreRepository storeRepository;
  private final ProductRepository productRepository;
  private final ReceiptConfirmService receiptConfirmService;
  private final ViewerContextResolver viewerContextResolver;
  private final ProductOverlayService productOverlayService;
  private final StoreOverlayService storeOverlayService;

  @QueryMapping
  public List<Receipt> myReceipts(@Argument Integer first, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.isAnonymous()) {
      return List.of();
    }
    int limit = first == null || first < 1 ? 20 : Math.min(first, 100);
    return receiptRepository.findByUploadedByUserIdOrderByCreatedAtDesc(viewer.userId(),
        PageRequest.of(0, limit));
  }

  @QueryMapping
  public Receipt receipt(@Argument Long id, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.isAnonymous()) {
      return null;
    }
    return receiptRepository.findById(id)
        .filter(receipt -> receipt.getUploadedByUserId().equals(viewer.userId()))
        .orElse(null);
  }

  @MutationMapping
  public ReceiptLine matchReceiptLine(@Argument Long lineId, @Argument Long productId,
      Authentication authentication) {
    return receiptConfirmService.matchLine(lineId, productId, requireViewer(authentication));
  }

  @MutationMapping
  public ReceiptLine skipReceiptLine(@Argument Long lineId, Authentication authentication) {
    return receiptConfirmService.skipLine(lineId, requireViewer(authentication));
  }

  @MutationMapping
  public Receipt setReceiptStore(@Argument Long receiptId, @Argument Long storeId,
      Authentication authentication) {
    return receiptConfirmService.setStore(receiptId, storeId, requireViewer(authentication));
  }

  @MutationMapping
  public ReceiptConfirmResult confirmReceipt(@Argument Long id, Authentication authentication) {
    return receiptConfirmService.confirm(id, requireViewer(authentication));
  }

  @BatchMapping(typeName = "Receipt", field = "lines")
  public Map<Receipt, List<ReceiptLine>> lines(List<Receipt> receipts) {
    Map<Long, List<ReceiptLine>> byReceipt = receiptLineRepository
        .findByReceiptIdInOrderByReceiptIdAscLineNoAsc(
            receipts.stream().map(Receipt::getId).toList())
        .stream()
        .collect(Collectors.groupingBy(ReceiptLine::getReceiptId));
    Map<Receipt, List<ReceiptLine>> result = new HashMap<>();
    receipts.forEach(receipt ->
        result.put(receipt, byReceipt.getOrDefault(receipt.getId(), List.of())));
    return result;
  }

  /** Provozovna je na účtence jen id, GraphQL typ Store se dotahuje tady (bez N+1). */
  @BatchMapping(typeName = "Receipt", field = "store")
  public Map<Receipt, Store> store(List<Receipt> receipts) {
    Map<Long, Store> stores = byId(receipts.stream()
        .map(Receipt::getStoreId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet()));
    Map<Receipt, Store> result = new HashMap<>();
    receipts.forEach(receipt -> {
      Store store = receipt.getStoreId() == null ? null : stores.get(receipt.getStoreId());
      result.put(receipt, store == null ? null : storeOverlayService.applyOverlay(store, null));
    });
    return result;
  }

  /**
   * Návrh katalogové položky. Prochází přes {@link ProductOverlayService} ze stejného důvodu
   * jako jinde: {@code core.product} smí mít od {@code createProductFromOff} NULL name —
   * syrová entita by poslala non-null pole se skutečným NULL.
   */
  @BatchMapping(typeName = "ReceiptLine", field = "matchedProduct")
  public Map<ReceiptLine, Product> matchedProduct(List<ReceiptLine> lines) {
    Map<Long, Product> products = productRepository.findWithBrandAndCategoryByIdIn(
            lines.stream()
                .map(ReceiptLine::getMatchedProductId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()))
        .stream()
        .collect(Collectors.toMap(Product::getId, product -> product));
    Map<ReceiptLine, Product> result = new HashMap<>();
    lines.forEach(line -> {
      Product product = line.getMatchedProductId() == null
          ? null
          : products.get(line.getMatchedProductId());
      result.put(line, product == null ? null : productOverlayService.applyOverlay(product, null));
    });
    return result;
  }

  @SchemaMapping(typeName = "ReceiptLineResult", field = "errorCode")
  public ErrorCode errorCode(ReceiptLineResult result) {
    return result.errorCode() == null ? null : ErrorCode.valueOf(result.errorCode());
  }

  private Map<Long, Store> byId(Collection<Long> ids) {
    return ids.isEmpty()
        ? Map.of()
        : storeRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Store::getId, store -> store));
  }

  private Long requireViewer(Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.isAnonymous()) {
      throw new ValidationException(ErrorCode.RECEIPT_REQUIRES_LOGIN);
    }
    return viewer.userId();
  }
}
