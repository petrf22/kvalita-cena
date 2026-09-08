package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.controller.ReceiptDocument;
import cz.kvalitacena.controller.ReceiptImportResult;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Příjem normalizované účtenky ({@code receipt-v1}) a její uložení jako NÁVRHU
 * (docs/ai.md, „AI nikdy nerozhoduje" — vytěžení nezakládá cenu, jen podklad k potvrzení).
 *
 * <p>Import je idempotentní podle otisku dokumentu: opakované nahrání téhož souboru vrátí
 * existující účtenku, nezaloží druhou kopii. Nástroj z {@code tools/uctenky} se tak dá
 * kdykoli spustit znovu nad celým adresářem.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptImportService {

  private static final int SUPPORTED_SCHEMA_VERSION = 1;

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository receiptLineRepository;
  private final StoreRepository storeRepository;
  private final RetailChainRepository chainRepository;
  private final ReceiptLineMatchingService matchingService;
  private final CurrencyResolver currencyResolver;
  private final ReceiptProperties receiptProperties;

  @Transactional
  public ReceiptImportResult importReceipt(ReceiptDocument document, AppUser uploader) {
    validate(document);

    byte[] fingerprint = fingerprint(document);
    Receipt existing = receiptRepository
        .findByUploadedByUserIdAndPayloadSha256(uploader.getId(), fingerprint)
        .orElse(null);
    if (existing != null) {
      return summarize(existing, receiptLineRepository.findByReceiptIdOrderByLineNoAsc(existing.getId()), true);
    }

    long today = receiptRepository.countByUploadedByUserIdAndCreatedAtAfter(
        uploader.getId(), OffsetDateTime.now().minusDays(1));
    if (today >= receiptProperties.getMaxPerDay()) {
      throw new ValidationException(ErrorCode.RECEIPT_DAILY_LIMIT_REACHED);
    }

    RetailChain chain = resolveChain(document);
    Store store = resolveStore(document, chain);

    // Součet se počítá ZNOVU na serveru — klientova hodnota je jen kontrola, ne zdroj pravdy.
    BigDecimal computed = document.lines().stream()
        .map(ReceiptDocument.Line::lineTotal)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean matches = document.printedTotal() != null
        && computed.subtract(document.printedTotal()).abs()
            .compareTo(receiptProperties.getTotalTolerance()) <= 0;

    Receipt receipt = receiptRepository.save(Receipt.builder()
        .uploadedByUserId(uploader.getId())
        .payloadSha256(fingerprint)
        .schemaVersion(document.schemaVersion())
        .profile(document.profile())
        .ocrModel(document.ocr() == null ? null : document.ocr().model())
        .imageSha256(document.ocr() == null ? null : document.ocr().imageSha256())
        .rawMerchantName(merchant(document, ReceiptDocument.Merchant::rawName))
        .rawStreet(merchant(document, ReceiptDocument.Merchant::street))
        .rawCity(merchant(document, ReceiptDocument.Merchant::city))
        .chainId(chain == null ? null : chain.getId())
        .storeId(store == null ? null : store.getId())
        .purchasedAt(document.purchasedAt())
        .currency(document.currency().toUpperCase(Locale.ROOT))
        .printedTotal(document.printedTotal())
        .computedTotal(computed)
        .totalMatches(matches)
        .status(ReceiptStatus.IMPORTED)
        .build());

    List<ReceiptLine> lines = new ArrayList<>(document.lines().size());
    for (ReceiptDocument.Line line : document.lines()) {
      lines.add(ReceiptLine.builder()
          .receiptId(receipt.getId())
          .lineNo(line.lineNo())
          .kind(parseKind(line.kind()))
          .rawLabel(line.rawLabel())
          .rawCode(line.rawCode())
          .quantity(line.quantity())
          .quantityBasis(parseBasis(line.quantityBasis()))
          .unitPrice(line.unitPrice())
          .lineTotal(line.lineTotal())
          .vatCode(line.vatCode())
          .appliesToLineNo(line.appliesToLineNo())
          .note(line.note())
          .build());
    }
    List<ReceiptLine> saved = receiptLineRepository.saveAll(lines);

    matchingService.matchAll(receipt, saved);
    receipt.setStatus(ReceiptStatus.MATCHED);

    return summarize(receipt, saved, false);
  }

  private void validate(ReceiptDocument document) {
    if (document.schemaVersion() == null || document.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
      throw new ValidationException(ErrorCode.RECEIPT_SCHEMA_VERSION_UNSUPPORTED,
          String.valueOf(document.schemaVersion()));
    }
    if (document.lines() == null || document.lines().isEmpty()) {
      throw new ValidationException(ErrorCode.RECEIPT_LINES_REQUIRED);
    }
    if (document.lines().size() > receiptProperties.getMaxLinesPerReceipt()) {
      throw new ValidationException(ErrorCode.RECEIPT_TOO_MANY_LINES,
          String.valueOf(receiptProperties.getMaxLinesPerReceipt()));
    }
    if (document.currency() == null || !currencyResolver.isSupported(document.currency())) {
      throw new ValidationException(ErrorCode.RECEIPT_CURRENCY_UNSUPPORTED,
          String.valueOf(document.currency()));
    }
    // observed_at znamená "kdy to uživatel VIDĚL" — účtenka z budoucnosti je rozsypané datum,
    // ne platný zápis (docs/datovy-model.md).
    if (document.purchasedAt() != null && document.purchasedAt().isAfter(OffsetDateTime.now())) {
      throw new ValidationException(ErrorCode.RECEIPT_PURCHASED_AT_IN_FUTURE);
    }
  }

  /**
   * Otisk celého dokumentu, ne jen obrázku: tentýž sken přepsaný novou verzí parseru je nová
   * účtenka, protože nese jiné řádky. Kdyby se počítal jen z {@code imageSha256}, oprava
   * parseru by se na server už nedostala.
   *
   * <p>Kanonický tvar se skládá ručně, ne serializací celého objektu — otisk pak nezávisí na
   * tom, jak zrovna Jackson řadí pole, a je vidět, co přesně „tatáž účtenka" znamená.
   */
  private static byte[] fingerprint(ReceiptDocument document) {
    ReceiptDocument.Merchant merchant = document.merchant();
    StringBuilder canonical = new StringBuilder()
        .append(document.schemaVersion()).append('\u001f')
        .append(document.profile()).append('\u001f')
        .append(document.currency()).append('\u001f')
        .append(document.purchasedAt()).append('\u001f')
        .append(document.printedTotal()).append('\u001f')
        // Hlavička je součást otisku: rozhoduje, ke které provozovně se účtenka připne,
        // takže její oprava je nová účtenka, ne tatáž.
        .append(merchant == null ? null : merchant.chainSlug()).append('\u001f')
        .append(merchant == null ? null : merchant.country()).append('\u001f')
        .append(merchant == null ? null : merchant.street()).append('\u001f')
        .append(merchant == null ? null : merchant.city()).append('\u001e');
    for (ReceiptDocument.Line line : document.lines()) {
      canonical.append(line.lineNo()).append('\u001f')
          .append(line.kind()).append('\u001f')
          .append(line.rawLabel()).append('\u001f')
          .append(line.rawCode()).append('\u001f')
          .append(line.quantity()).append('\u001f')
          .append(line.quantityBasis()).append('\u001f')
          .append(line.unitPrice()).append('\u001f')
          .append(line.lineTotal()).append('\u001e');
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 není k dispozici", e);
    }
  }

  /**
   * Provozovna se DOHLEDÁVÁ, nikdy nezakládá — zakládání obchodu má vlastní denní limity
   * a práh potvrzení (docs/reputace.md) a hlavička účtenky na ně není důkaz. Když se nenajde,
   * účtenka se uloží bez ní a obchod vybere člověk při potvrzení.
   */
  private Store resolveStore(ReceiptDocument document, RetailChain chain) {
    if (chain == null || document.merchant() == null || document.merchant().city() == null) {
      return null;
    }
    return storeRepository.findByChainCityAndStreet(chain.getId(), chain.getCountry(),
        document.merchant().city(), document.merchant().street()).orElse(null);
  }

  private RetailChain resolveChain(ReceiptDocument document) {
    if (document.merchant() == null || document.merchant().chainSlug() == null
        || document.merchant().country() == null) {
      return null;
    }
    return chainRepository.findFirstBySlugAndCountry(document.merchant().chainSlug(),
        document.merchant().country().toUpperCase(Locale.ROOT)).orElse(null);
  }

  private static String merchant(ReceiptDocument document,
      java.util.function.Function<ReceiptDocument.Merchant, String> getter) {
    return document.merchant() == null ? null : getter.apply(document.merchant());
  }

  private static ReceiptLineKind parseKind(String value) {
    try {
      return value == null ? ReceiptLineKind.UNKNOWN : ReceiptLineKind.valueOf(value);
    } catch (IllegalArgumentException e) {
      return ReceiptLineKind.UNKNOWN;
    }
  }

  private static QuantityBasis parseBasis(String value) {
    if (value == null) {
      return null;
    }
    try {
      return QuantityBasis.valueOf(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static ReceiptImportResult summarize(Receipt receipt, List<ReceiptLine> lines,
      boolean alreadyImported) {
    int matched = 0;
    int suggested = 0;
    int unmatched = 0;
    for (ReceiptLine line : lines) {
      switch (line.getMatchStatus()) {
        case MATCHED, IMPORTED -> matched++;
        case SUGGESTED -> suggested++;
        case UNMATCHED -> unmatched++;
        case SKIPPED -> { }
      }
    }
    return new ReceiptImportResult(receipt.getId(), alreadyImported, receipt.isTotalMatches(),
        lines.size(), matched, suggested, unmatched);
  }
}
