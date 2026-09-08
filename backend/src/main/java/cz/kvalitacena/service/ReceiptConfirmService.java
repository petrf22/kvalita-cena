package cz.kvalitacena.service;

import cz.kvalitacena.controller.ReceiptConfirmResult;
import cz.kvalitacena.controller.ReceiptLineResult;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.AppException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Potvrzení účtenky člověkem — teprve tady vznikají cenové zápisy. Vytěžení samo cenu
 * nezakládá (docs/ai.md, „AI nikdy nerozhoduje").
 *
 * <p>Metoda ZÁMĚRNĚ není {@code @Transactional}: účtenka není atomická dávka. Každý řádek
 * commituje sám ({@link ReceiptLineImporter}), takže jedna duplicita nebo jedna vadná položka
 * nezahodí zbytek nákupu — dávková sémantika {@code submitObservations} na účtenku nesedí
 * (docs/rozvoj.md).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptConfirmService {

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository receiptLineRepository;
  private final StoreRepository storeRepository;
  private final ProductRepository productRepository;
  private final AppUserRepository appUserRepository;
  private final ReceiptLineImporter receiptLineImporter;
  private final ReceiptLineMatchingService matchingService;

  /** Ruční přiřazení řádku ke katalogové položce — přebíjí cokoli, co našla kaskáda. */
  @Transactional
  public ReceiptLine matchLine(Long lineId, Long productId, Long viewerId) {
    ReceiptLine line = ownedLine(lineId, viewerId);
    if (!productRepository.existsById(productId)) {
      throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND);
    }
    line.setMatchedProductId(productId);
    line.setMatchSource(ReceiptMatchSource.MANUAL);
    line.setMatchScore(null);
    line.setMatchStatus(ReceiptLineMatchStatus.MATCHED);
    return receiptLineRepository.save(line);
  }

  /** Řádek, který se zapisovat nemá (nečitelný, nezajímavý, ne cena zboží). */
  @Transactional
  public ReceiptLine skipLine(Long lineId, Long viewerId) {
    ReceiptLine line = ownedLine(lineId, viewerId);
    line.setMatchStatus(ReceiptLineMatchStatus.SKIPPED);
    return receiptLineRepository.save(line);
  }

  /** Provozovna účtenky — dohledaná při importu nebo ručně vybraná před potvrzením. */
  @Transactional
  public Receipt setStore(Long receiptId, Long storeId, Long viewerId) {
    Receipt receipt = ownedReceipt(receiptId, viewerId);
    Store store = storeRepository.findById(storeId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
    receipt.setStoreId(store.getId());
    receipt.setChainId(store.getChain() == null ? null : store.getChain().getId());
    // Rozsah párování se změnou obchodu mění, takže se kaskáda pustí znovu — ručně přiřazené
    // a přeskočené řádky zůstávají, ty rozhodl člověk.
    List<ReceiptLine> lines = receiptLineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
    lines.stream()
        .filter(line -> line.getKind() == ReceiptLineKind.ITEM)
        .filter(line -> line.getMatchStatus() == ReceiptLineMatchStatus.UNMATCHED
            || line.getMatchStatus() == ReceiptLineMatchStatus.SUGGESTED)
        .forEach(line -> matchingService.match(receipt, line));
    receiptLineRepository.saveAll(lines);
    return receiptRepository.save(receipt);
  }

  public ReceiptConfirmResult confirm(Long receiptId, Long viewerId) {
    Receipt receipt = ownedReceipt(receiptId, viewerId);
    if (receipt.getStatus() == ReceiptStatus.CONFIRMED) {
      throw new ValidationException(ErrorCode.RECEIPT_ALREADY_CONFIRMED);
    }
    // Účtenka, jejíž součet nesedí, je rozsypané OCR — do cen se nezapisuje. Na reálných
    // účtenkách sedí Σ(položky + slevy) na haléř, takže je to spolehlivá brána, ne opatrnost.
    if (!receipt.isTotalMatches()) {
      throw new ValidationException(ErrorCode.RECEIPT_TOTAL_MISMATCH);
    }
    if (receipt.getStoreId() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_STORE_REQUIRED);
    }
    if (receipt.getPurchasedAt() == null) {
      throw new ValidationException(ErrorCode.RECEIPT_PURCHASED_AT_REQUIRED);
    }
    Store store = storeRepository.findById(receipt.getStoreId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.STORE_NOT_FOUND));
    AppUser uploader = appUserRepository.findById(receipt.getUploadedByUserId())
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));

    List<ReceiptLineResult> results = new ArrayList<>();
    int written = 0;
    int skipped = 0;
    int failed = 0;
    for (ReceiptLine line : receiptLineRepository.findByReceiptIdOrderByLineNoAsc(receiptId)) {
      ReceiptLineResult result = confirmLine(receipt, line, store, uploader);
      if (result == null) {
        continue; // sleva ani nerozpoznaný řádek nejsou cena, do souhrnu nepatří
      }
      results.add(result);
      switch (result.outcome()) {
        case "WRITTEN" -> written++;
        case "FAILED" -> failed++;
        default -> skipped++;
      }
    }

    receipt.setStatus(ReceiptStatus.CONFIRMED);
    receipt.setConfirmedAt(OffsetDateTime.now());
    receiptRepository.save(receipt);
    return new ReceiptConfirmResult(receiptId, written, skipped, failed, results);
  }

  private ReceiptLineResult confirmLine(Receipt receipt, ReceiptLine line, Store store,
      AppUser uploader) {
    if (line.getKind() != ReceiptLineKind.ITEM) {
      return null;
    }
    if (line.getMatchStatus() == ReceiptLineMatchStatus.SKIPPED) {
      return outcome(line, "SKIPPED_BY_USER");
    }
    if (line.getMatchStatus() == ReceiptLineMatchStatus.IMPORTED) {
      return new ReceiptLineResult(line.getId(), line.getLineNo(), line.getRawLabel(),
          "SKIPPED_DUPLICATE", line.getObservationId(), null);
    }
    // SUGGESTED je NÁVRH, ne shoda — bez potvrzení člověkem se nezapisuje (docs/ai.md).
    if (line.getMatchStatus() != ReceiptLineMatchStatus.MATCHED
        || line.getMatchedProductId() == null || line.getUnitPrice() == null) {
      return outcome(line, "SKIPPED_UNMATCHED");
    }
    try {
      return receiptLineImporter.importLine(receipt, line, store, uploader);
    } catch (AppException e) {
      // Chytá se AŽ TADY, vně hranice REQUIRES_NEW: uvnitř transakční metody by odchycení
      // transakci neodznačilo a její vlastní commit by spadl na UnexpectedRollbackException.
      boolean duplicate = e.getCode() == ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY
          || e.getCode() == ErrorCode.OBSERVATION_ALREADY_SUBMITTED_TODAY;
      log.debug("Řádek {} účtenky {} se nezapsal: {}", line.getLineNo(), receipt.getId(),
          e.getCode());
      return new ReceiptLineResult(line.getId(), line.getLineNo(), line.getRawLabel(),
          duplicate ? "SKIPPED_DUPLICATE" : "FAILED", null, e.getCode().name());
    }
  }

  private static ReceiptLineResult outcome(ReceiptLine line, String outcome) {
    return new ReceiptLineResult(line.getId(), line.getLineNo(), line.getRawLabel(), outcome,
        null, null);
  }

  /** Účtenka je vidět jen svému majiteli — je to přehled cizího nákupu (docs/soukromi.md). */
  private Receipt ownedReceipt(Long receiptId, Long viewerId) {
    Receipt receipt = receiptRepository.findById(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    if (viewerId == null || !receipt.getUploadedByUserId().equals(viewerId)) {
      // Cizí účtenka se tváří jako neexistující, nikdy 403 — jinak by šlo zvenčí odvodit,
      // že někdo daný nákup nahrál (stejné pravidlo jako u skrytých fotek a recenzí).
      throw new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND);
    }
    return receipt;
  }

  private ReceiptLine ownedLine(Long lineId, Long viewerId) {
    ReceiptLine line = receiptLineRepository.findById(lineId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_LINE_NOT_FOUND));
    ownedReceipt(line.getReceiptId(), viewerId);
    return line;
  }
}
