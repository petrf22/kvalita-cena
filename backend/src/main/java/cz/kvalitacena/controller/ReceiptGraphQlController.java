package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductAliasStatus;
import cz.kvalitacena.db.entity.ProductStoreLabel;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.ProductOverlayService;
import cz.kvalitacena.service.ReceiptMatchingService;
import cz.kvalitacena.service.ReceiptReviewService;
import cz.kvalitacena.service.ReceiptService;
import cz.kvalitacena.service.StoreOverlayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Účtenky v GraphQL (docs/datovy-model.md, „Účtenky"). Všechno kromě {@code Product.storeLabels}
 * vidí jen vlastník — cizí účtenka je {@code null}/NOT_FOUND, nikdy FORBIDDEN.
 */
@Controller
@RequiredArgsConstructor
public class ReceiptGraphQlController {

  private static final int MAX_SUGGESTIONS = 20;

  private final ReceiptService receiptService;
  private final ReceiptReviewService reviewService;
  private final ReceiptMatchingService matchingService;
  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository lineRepository;
  private final ProductStoreLabelRepository labelRepository;
  private final ProductRepository productRepository;
  private final StoreRepository storeRepository;
  private final RetailChainRepository chainRepository;
  private final ProductOverlayService productOverlayService;
  private final StoreOverlayService storeOverlayService;
  private final ViewerContextResolver viewerContextResolver;
  private final HttpServletRequest servletRequest;

  // --- dotazy

  @QueryMapping
  public ReceiptService.ReceiptPage myReceipts(@Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    return receiptService.myReceipts(viewer(authentication).publicUid(), first, offset);
  }

  @QueryMapping
  public Receipt receipt(@Argument Long id, Authentication authentication) {
    ViewerContext viewer = viewer(authentication);
    if (viewer.publicUid() == null) {
      return null;
    }
    try {
      return receiptService.get(viewer.publicUid(), id);
    } catch (NotFoundException e) {
      return null;
    }
  }

  @QueryMapping
  public ReceiptService.ReceiptSummary receiptSummary(Authentication authentication) {
    return receiptService.summary(viewer(authentication).publicUid());
  }

  @QueryMapping
  public List<Product> receiptLineSuggestions(@Argument Long lineId, @Argument String query, @Argument Integer first,
      Authentication authentication) {
    ViewerContext viewer = viewer(authentication);
    ReceiptLine line = lineRepository.findById(lineId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_LINE_NOT_FOUND));
    Receipt receipt = receiptService.get(viewer.publicUid(), line.getReceiptId());
    Long storeId = receipt.getStoreId() != null ? receipt.getStoreId() : receipt.getSuggestedStoreId();
    Store store = storeId == null ? null : storeRepository.findById(storeId).orElse(null);
    int limit = Math.max(1, Math.min(first == null ? 10 : first, MAX_SUGGESTIONS));
    List<Product> found = matchingService.suggestions(line, store, query, viewer.userId(), limit).stream()
        .filter(p -> p.getHiddenAt() == null || viewer.moderator()
            || Objects.equals(p.getCreatedByUserId(), viewer.userId()))
        .toList();
    return productOverlayService.applyOverlay(found, viewer.userId());
  }

  // --- mutace

  @MutationMapping
  public Receipt updateReceiptHeader(@Argument Long id, @Argument Long storeId,
      @Argument java.time.OffsetDateTime purchasedAt, Authentication authentication) {
    return reviewService.updateHeader(viewer(authentication).publicUid(), id, storeId, purchasedAt);
  }

  @MutationMapping
  public ReceiptLine matchReceiptLine(@Argument Long lineId, @Argument Long productId, @Argument Boolean newProduct,
      Authentication authentication) {
    return reviewService.matchLine(viewer(authentication).publicUid(), lineId, productId, Boolean.TRUE.equals(newProduct));
  }

  @MutationMapping
  public ReceiptLine ignoreReceiptLine(@Argument Long lineId, Authentication authentication) {
    return reviewService.ignoreLine(viewer(authentication).publicUid(), lineId);
  }

  @MutationMapping
  public ReceiptLine unmatchReceiptLine(@Argument Long lineId, Authentication authentication) {
    return reviewService.unmatchLine(viewer(authentication).publicUid(), lineId);
  }

  @MutationMapping
  public ReceiptReviewService.ConfirmResult confirmReceiptLines(@Argument Long receiptId, @Argument List<Long> lineIds,
      Authentication authentication) {
    String clientKind = servletRequest.getHeader("X-Client-Kind");
    ObservationSource source = "ANDROID".equalsIgnoreCase(clientKind) ? ObservationSource.MOBILE : ObservationSource.WEB;
    return reviewService.confirmLines(viewer(authentication).publicUid(), receiptId, lineIds, source);
  }

  @MutationMapping
  public Receipt markReceiptSeen(@Argument Long id, Authentication authentication) {
    return receiptService.markSeen(viewer(authentication).publicUid(), id);
  }

  @MutationMapping
  public boolean deleteReceipt(@Argument Long id, Authentication authentication) {
    receiptService.delete(viewer(authentication).publicUid(), id);
    return true;
  }

  // --- pole typu Receipt

  @BatchMapping(typeName = "Receipt", field = "lines")
  public Map<Receipt, List<ReceiptLine>> lines(List<Receipt> receipts) {
    Map<Long, List<ReceiptLine>> byReceipt = linesByReceipt(receipts);
    Map<Receipt, List<ReceiptLine>> result = new LinkedHashMap<>();
    receipts.forEach(r -> result.put(r, byReceipt.getOrDefault(r.getId(), List.of())));
    return result;
  }

  @BatchMapping(typeName = "Receipt", field = "lineCounts")
  public Map<Receipt, ReceiptLineCounts> lineCounts(List<Receipt> receipts) {
    Map<Long, List<ReceiptLine>> byReceipt = linesByReceipt(receipts);
    Map<Receipt, ReceiptLineCounts> result = new LinkedHashMap<>();
    for (Receipt r : receipts) {
      List<ReceiptLine> lines = byReceipt.getOrDefault(r.getId(), List.of());
      result.put(r, new ReceiptLineCounts(lines.size(),
          count(lines, ReceiptLineStatus.UNMATCHED), count(lines, ReceiptLineStatus.SUGGESTED),
          count(lines, ReceiptLineStatus.MATCHED), count(lines, ReceiptLineStatus.IGNORED)));
    }
    return result;
  }

  @SchemaMapping(typeName = "Receipt", field = "store")
  public Store store(Receipt receipt, Authentication authentication) {
    return overlaidStore(receipt.getStoreId(), authentication);
  }

  @SchemaMapping(typeName = "Receipt", field = "suggestedStore")
  public Store suggestedStore(Receipt receipt, Authentication authentication) {
    return overlaidStore(receipt.getSuggestedStoreId(), authentication);
  }

  @SchemaMapping(typeName = "Receipt", field = "imagePaths")
  public List<String> imagePaths(Receipt receipt) {
    return receiptService.images(receipt.getId()).stream()
        .map(i -> "/api/receipts/" + receipt.getId() + "/images/" + i.getSeq())
        .toList();
  }

  @SchemaMapping(typeName = "Receipt", field = "unseen")
  public boolean unseen(Receipt receipt) {
    if (receipt.getProcessedAt() == null) {
      return false;
    }
    if (receipt.getSeenAt() == null) {
      return true;
    }
    return lineRepository.findByReceiptIdOrderByLineNoAsc(receipt.getId()).stream()
        .anyMatch(l -> l.getStatus() == ReceiptLineStatus.SUGGESTED && l.getUpdatedAt().isAfter(receipt.getSeenAt()));
  }

  // --- pole typu ReceiptLine

  @SchemaMapping(typeName = "ReceiptLine", field = "priceKind")
  public PriceKind priceKind(ReceiptLine line) {
    return line.getPriceKindGuess();
  }

  @BatchMapping(typeName = "ReceiptLine", field = "product")
  public Map<ReceiptLine, Product> lineProduct(List<ReceiptLine> lines, Authentication authentication) {
    Long viewerId = viewer(authentication).userId();
    List<Long> ids = lines.stream().map(ReceiptLine::getProductId).filter(Objects::nonNull).distinct().toList();
    Map<Long, Product> byId = productOverlayService.applyOverlay(productRepository.findAllById(ids), viewerId).stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<ReceiptLine, Product> result = new LinkedHashMap<>();
    lines.forEach(l -> result.put(l, l.getProductId() == null ? null : byId.get(l.getProductId())));
    return result;
  }

  // --- Product.storeLabels (veřejné — označení je katalogový údaj, ne údaj o nákupu)

  @BatchMapping(typeName = "Product", field = "storeLabels")
  public Map<Product, List<ProductStoreLabelView>> storeLabels(List<Product> products) {
    List<Long> ids = products.stream().map(Product::getId).toList();
    List<ProductStoreLabel> labels = labelRepository.findByProductIdInOrderByLastSeenAtDesc(ids).stream()
        .filter(l -> l.getStatus() == ProductAliasStatus.ACTIVE)
        .toList();
    Map<Long, String> chainNames = chainRepository.findAllById(labels.stream().map(ProductStoreLabel::getChainId)
            .filter(Objects::nonNull).distinct().toList()).stream()
        .collect(Collectors.toMap(RetailChain::getId, RetailChain::getName));
    Map<Long, String> storeNames = storeRepository.findAllById(labels.stream().map(ProductStoreLabel::getStoreId)
            .filter(Objects::nonNull).distinct().toList()).stream()
        .collect(Collectors.toMap(Store::getId, Store::getName));
    Map<Long, List<ProductStoreLabelView>> byProduct = labels.stream().collect(Collectors.groupingBy(
        ProductStoreLabel::getProductId,
        Collectors.mapping(l -> new ProductStoreLabelView(l.getLabel(),
            l.getChainId() == null ? null : chainNames.get(l.getChainId()),
            l.getStoreId() == null ? null : storeNames.get(l.getStoreId()),
            l.getLastSeenAt()), Collectors.toList())));
    Map<Product, List<ProductStoreLabelView>> result = new LinkedHashMap<>();
    products.forEach(p -> result.put(p, byProduct.getOrDefault(p.getId(), List.of())));
    return result;
  }

  private Map<Long, List<ReceiptLine>> linesByReceipt(List<Receipt> receipts) {
    return lineRepository.findByReceiptIdInOrderByLineNoAsc(receipts.stream().map(Receipt::getId).toList()).stream()
        .collect(Collectors.groupingBy(ReceiptLine::getReceiptId));
  }

  private static int count(List<ReceiptLine> lines, ReceiptLineStatus status) {
    return (int) lines.stream().filter(l -> l.getStatus() == status).count();
  }

  private Store overlaidStore(Long storeId, Authentication authentication) {
    if (storeId == null) {
      return null;
    }
    Long viewerId = viewer(authentication).userId();
    return storeRepository.findById(storeId).map(s -> storeOverlayService.applyOverlay(s, viewerId)).orElse(null);
  }

  private ViewerContext viewer(Authentication authentication) {
    return viewerContextResolver.resolve(authentication);
  }
}
