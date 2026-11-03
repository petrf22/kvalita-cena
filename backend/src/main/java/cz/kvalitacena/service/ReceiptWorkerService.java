package cz.kvalitacena.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.controller.worker.WorkerArchiveCandidate;
import cz.kvalitacena.controller.worker.WorkerCategory;
import cz.kvalitacena.controller.worker.WorkerReceiptJob;
import cz.kvalitacena.controller.worker.WorkerReceiptResult;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.LabelDisplaySource;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptImage;
import cz.kvalitacena.db.entity.ReceiptJob;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineHint;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.entity.ReceiptReviewStatus;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ReceiptImageRepository;
import cz.kvalitacena.db.repo.ReceiptLineHintRepository;
import cz.kvalitacena.db.repo.ReceiptJobRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serverová strana pull workeru (docs/ai.md, „Kde to běží" a „Účtenky"). Worker si úlohy
 * vyzvedává s leasem, výsledek zapíše jako NÁVRH (řádky účtenky) — cenu nezakládá nikdy.
 * Když worker neběží, účtenky jen čekají ve frontě, appka funguje beze změny.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptWorkerService {

  private static final int MAX_TEXT = 200;
  /** NUMERIC(12,3) u net_content_hint_value — větší číslo je chyba čtení, ne gramáž. */
  private static final BigDecimal MAX_NET_CONTENT_HINT = new BigDecimal("1000000");
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
  /** Strop jednoho requeue — hromadné nové rozpoznání nesmí zahltit frontu na dny. */
  static final int MAX_REQUEUE = 500;

  private final ReceiptJobRepository jobRepository;
  private final ReceiptRepository receiptRepository;
  private final ReceiptImageRepository imageRepository;
  private final ReceiptLineRepository lineRepository;
  private final StoreRepository storeRepository;
  private final ReceiptMatchingService matchingService;
  private final ReceiptService receiptService;
  private final ReceiptProperties receiptProperties;
  private final CategoryRepository categoryRepository;
  private final ReceiptLineHintRepository hintRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Transactional
  public List<WorkerReceiptJob> lease(Integer requested) {
    int limit = Math.max(1, Math.min(requested == null ? 1 : requested, receiptProperties.getMaxLeaseBatch()));
    OffsetDateTime now = OffsetDateTime.now();
    // Na milisekundy: konec lease se workeru vrací jako identifikátor pronájmu (fail) a musí
    // se po cestě JSON → DB (mikrosekundy) porovnat přesně.
    OffsetDateTime leaseUntil = now.plusMinutes(receiptProperties.getLeaseMinutes()).truncatedTo(ChronoUnit.MILLIS);
    List<WorkerReceiptJob> result = new ArrayList<>();
    for (ReceiptJob job : jobRepository.lockAvailable(now, limit)) {
      Optional<Receipt> receipt = receiptRepository.findForUpdateById(job.getReceiptId());
      boolean imagesGone = receipt.isEmpty() || receipt.get().getImagesDeletedAt() != null;
      if (imagesGone && (receipt.isEmpty() || job.getRawText() == null)) {
        // Obrázky zmizely (retence) a uložený text k novému rozboru není — není co číst.
        job.setProcessedAt(now);
        job.setLastError("images gone");
        receipt.ifPresent(r -> failUnlessReprocessing(r, now));
        continue;
      }
      if (job.getAttempts() >= receiptProperties.getMaxAttempts()) {
        // Vrátila se jen proto, že vypršel lease — worker na ní spadl nebo zamrzl bez /fail.
        // Bez téhle větve by se vyzvedávala dokola a limit pokusů by neplatil nikdy.
        job.setProcessedAt(now);
        job.setLeaseUntil(null);
        job.setLastError("lease expired after " + job.getAttempts() + " attempts");
        failUnlessReprocessing(receipt.get(), now);
        continue;
      }
      job.setLeasedAt(now);
      job.setLeaseUntil(leaseUntil);
      job.setAttempts((short) (job.getAttempts() + 1));
      Receipt r = receipt.get();
      if (!isReprocessing(r)) {
        // Přepracovávaná účtenka zůstává PROCESSED — vlastník s ní dál pracuje, dokud nedorazí
        // nová verze (docs/ai.md, „Nové rozpoznání").
        r.setProcessingStatus(ReceiptProcessingStatus.PROCESSING);
      }
      result.add(imagesGone
          ? new WorkerReceiptJob(r.getId(), r.getCapturedAt(), leaseUntil, List.of(), job.getRawText())
          : new WorkerReceiptJob(r.getId(), r.getCapturedAt(), leaseUntil,
              imagePaths(r.getId(), imageRepository.findByReceiptIdOrderBySeqAsc(r.getId())), null));
    }
    return result;
  }

  /** Výsledek {@link #requeue}: co se vrátilo do fronty a co ne (s důvodem pro výpis workeru). */
  public record RequeueResult(List<Long> requeued, List<Skipped> skipped) {
    public record Skipped(Long receiptId, String reason) {
    }
  }

  /**
   * Vrátí už zpracované účtenky k novému rozpoznání — po opravě OCR nebo normalizace ve workeru
   * (docs/ai.md, „Nové rozpoznání"). Spouští provozovatel tokenem workeru, vlastníka to
   * nezatěžuje: PROCESSED účtenka zůstává PROCESSED se starými řádky, dokud nedorazí nová verze,
   * a řádky, na které uživatel sáhl, nová verze nepřepíše ({@link ReceiptLineMerger}). Účtenka
   * bez řádků (FAILED, UNREADABLE, NOT_A_RECEIPT) se zpracuje od nuly. Bez obrázků (retence)
   * worker znovu rozebere jen uložený syrový text.
   *
   * @param receiptIds     konkrétní účtenky (ID z logu workeru)
   * @param processedSince nebo všechny zpracované od tohoto okamžiku (nejvýš {@code MAX_REQUEUE})
   */
  @Transactional
  public RequeueResult requeue(List<Long> receiptIds, OffsetDateTime processedSince) {
    List<Long> ids = new ArrayList<>(Optional.ofNullable(receiptIds).orElse(List.of()));
    if (processedSince != null) {
      ids.addAll(jobRepository.findFinishedReceiptIdsSince(processedSince, MAX_REQUEUE));
    }
    OffsetDateTime now = OffsetDateTime.now();
    List<Long> requeued = new ArrayList<>();
    List<RequeueResult.Skipped> skipped = new ArrayList<>();
    for (Long receiptId : ids.stream().distinct().limit(MAX_REQUEUE).toList()) {
      Optional<ReceiptJob> job = jobRepository.findForUpdateByReceiptId(receiptId);
      Optional<Receipt> receipt = receiptRepository.findForUpdateById(receiptId);
      if (job.isEmpty() || receipt.isEmpty()) {
        skipped.add(new RequeueResult.Skipped(receiptId, "not found"));
        continue;
      }
      if (job.get().getProcessedAt() == null) {
        skipped.add(new RequeueResult.Skipped(receiptId, "already queued"));
        continue;
      }
      if (receipt.get().getImagesDeletedAt() != null && job.get().getRawText() == null) {
        skipped.add(new RequeueResult.Skipped(receiptId, "images deleted and no stored text"));
        continue;
      }
      ReceiptJob j = job.get();
      j.setProcessedAt(null);
      j.setLeaseUntil(null);
      j.setAttempts((short) 0);
      j.setEnqueuedAt(now);
      j.setRequeuedAt(now);
      j.setLastError(null);
      if (receipt.get().getProcessingStatus() != ReceiptProcessingStatus.PROCESSED) {
        receipt.get().setProcessingStatus(ReceiptProcessingStatus.QUEUED);
      }
      requeued.add(receiptId);
    }
    // queueNotifier se nevolá: frontu naplnil sám provozovatel, e-mail by ho jen upozornil na to,
    // co právě udělal.
    log.info("Nové rozpoznání — vráceno do fronty: {}, přeskočeno: {}", requeued.size(), skipped.size());
    return new RequeueResult(requeued, skipped);
  }

  @Transactional
  public void submitResult(Long receiptId, WorkerReceiptResult result) {
    ReceiptJob job = jobRepository.findForUpdateByReceiptId(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    // Zámek účtenky: souběžná úprava hlavičky vlastníkem (ReceiptReviewService.updateHeader) se
    // nesmí ztratit pod zápisem z dřív načteného snapshotu — rozhodnutí „doplnit jen, když chybí"
    // níž tak platí nad čerstvým stavem. Pořadí zámků: úloha → účtenka → řádky.
    Receipt receipt = receiptRepository.findForUpdateById(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    if (result == null || result.status() == null || !result.status().isFinished()
        || result.status() == ReceiptProcessingStatus.FAILED) {
      throw new ValidationException(ErrorCode.RECEIPT_RESULT_INVALID);
    }
    if (job.getProcessedAt() != null) {
      // Výsledek už přišel (worker nedostal odpověď a poslal ho znovu, nebo pozdě po vypršeném
      // leasu) nebo úloha skončila jako FAILED — první zápis platí, řádky se nepřepisují.
      log.warn("Výsledek účtenky {} přišel k už ukončené úloze — ignoruje se", receiptId);
      return;
    }
    OffsetDateTime now = OffsetDateTime.now();
    boolean reprocessing = isReprocessing(receipt);
    job.setProcessedAt(now);
    job.setLeaseUntil(null);
    job.setLastError(null);
    if (reprocessing && result.status() != ReceiptProcessingStatus.PROCESSED) {
      // Nová verze workeru účtenku nepřečetla — horší výsledek nesmí smazat ten, se kterým
      // vlastník pracuje.
      job.setLastError(truncate("nové rozpoznání dalo " + result.status() + ", platí původní výsledek", 500));
      log.warn("Nové rozpoznání účtenky {} dalo {} — ponechán původní výsledek", receiptId, result.status());
      return;
    }
    // Textový režim (obrázky po retenci) model neposílá a syrový text nemusí po novém rozboru
    // vzniknout — přepracování proto starou hodnotu nahradí jen novou, ne prázdnem.
    if (!reprocessing || result.model() != null) {
      job.setModel(truncate(result.model(), 100));
    }
    if (!reprocessing || result.rawText() != null) {
      job.setRawText(result.rawText());
    }
    job.setExtraction(toJson(result.extraction()));

    // Zámek řádků: souběžné potvrzení nesmí zapsat cenu z řádku, který se právě nahrazuje.
    List<ReceiptLine> existing = lineRepository.findForUpdateByReceiptId(receiptId);

    if (result.status() != ReceiptProcessingStatus.PROCESSED) {
      // Řádky, na které uživatel sáhl, stroj nikdy nemaže — ani tady.
      lineRepository.deleteAll(ReceiptLineMerger.merge(existing, List.of()).removed());
      finish(receipt, result.status(), now);
      return;
    }

    if (reprocessing) {
      // Údaje, které potvrzuje uživatel (obchod, čas nákupu), se jen doplňují; syrová hlavička
      // se nahradí jen novou hodnotou — z textu bez patičky by např. datum nákupu chybělo.
      receipt.setStoreNameRaw(firstNonNull(truncate(result.storeName(), 200), receipt.getStoreNameRaw()));
      receipt.setStoreAddressRaw(firstNonNull(truncate(result.storeAddress(), 300), receipt.getStoreAddressRaw()));
      receipt.setStoreIcoRaw(firstNonNull(truncate(result.storeIco(), 20), receipt.getStoreIcoRaw()));
      if (receipt.getPurchasedAt() == null) {
        receipt.setPurchasedAt(purchasedAt(result, now));
      }
      receipt.setTotalAmount(firstNonNull(amount(result.totalAmount()), receipt.getTotalAmount()));
    } else {
      receipt.setStoreNameRaw(truncate(result.storeName(), 200));
      receipt.setStoreAddressRaw(truncate(result.storeAddress(), 300));
      receipt.setStoreIcoRaw(truncate(result.storeIco(), 20));
      receipt.setPurchasedAt(purchasedAt(result, now));
      receipt.setTotalAmount(amount(result.totalAmount()));
    }
    // Měnu z workeru jen předpokládá profil (dnes vždy CZK, tools/uctenky/README.md), kdežto
    // obchod potvrzený uživatelem ji určuje (ReceiptReviewService.updateHeader) — ta se nepřepisuje.
    if (receipt.getStoreId() == null) {
      receipt.setCurrency(reprocessing
          ? firstNonNull(currency(result.currency()), receipt.getCurrency())
          : currency(result.currency()));
    }

    List<ReceiptLine> lines = new ArrayList<>();
    List<WorkerReceiptResult.Line> sources = new ArrayList<>();
    for (WorkerReceiptResult.Line in : Optional.ofNullable(result.lines()).orElse(List.of())) {
      if (lines.size() >= receiptProperties.getMaxLines()) {
        break;
      }
      String label = in == null ? null : truncate(in.label(), MAX_TEXT);
      if (label == null || label.isBlank()) {
        continue;
      }
      lines.add(ReceiptLine.builder()
          .receiptId(receiptId)
          .labelRaw(label.trim())
          .labelDisplay(blankToNull(truncate(in.labelDisplay(), MAX_TEXT)))
          .labelDisplaySource(enumOrNull(LabelDisplaySource.class, in.labelDisplaySource()))
          .netContentHintValue(netContentHint(in) != null ? in.netContentValue() : null)
          .netContentHintUom(netContentHint(in))
          .internalCode(truncate(in.internalCode(), 40))
          .quantity(quantity(in.quantity()))
          .quantityUnit(in.quantityUnit() != null ? in.quantityUnit() : QuantityUnit.PCS)
          .unitPrice(amount(in.unitPrice()))
          .lineTotal(amount(in.lineTotal()))
          .discountAmount(amount(in.discountAmount() == null ? null : in.discountAmount().abs()))
          .priceKindGuess(in.priceKind() != null ? in.priceKind() : PriceKind.REGULAR)
          .build());
      sources.add(in);
    }

    // Obchod potvrzený uživatelem má přednost před návrhem — návrh se přepočítá jen bez něj.
    Store store;
    if (receipt.getStoreId() != null) {
      store = storeRepository.findById(receipt.getStoreId()).orElse(null);
    } else {
      Optional<Store> suggested = matchingService.suggestStore(receipt);
      receipt.setSuggestedStoreId(suggested.map(Store::getId).orElse(null));
      store = suggested.orElse(null);
    }
    matchingService.matchLines(lines, store, receipt.getOwnerUserId());

    ReceiptLineMerger.Result merged = ReceiptLineMerger.merge(existing, lines);
    List<ReceiptLine> added = merged.added().stream().map(lines::get).toList();
    if (reprocessing && ReceiptLineMerger.sameContent(merged.removed(), added)) {
      // Nová verze dala totéž — řádky zůstanou, ať se účtenka vlastníkovi nerozsvítí jako nová.
      // Vodítka formuláře nového zboží (gramáž z tisku, návrh modelu) signatura řádku nezná,
      // takže se obnoví zvlášť; párování je stejné jako v sameContent.
      List<ReceiptLine> before = merged.removed().stream().sorted(Comparator.comparing(ReceiptLine::getLineNo))
          .toList();
      refreshGuidance(before, added, merged.added().stream().map(sources::get).toList(), job.getModel());
      log.info("Nové rozpoznání účtenky {} beze změny řádků, vodítka obnovena (model {})", receiptId,
          job.getModel());
      return;
    }
    replaceLines(merged);
    saveHints(added, merged.added().stream().map(sources::get).toList(), job.getModel());
    if (reprocessing) {
      if (receipt.getReviewStatus() != ReceiptReviewStatus.PENDING) {
        receipt.setReviewStatus(ReceiptReviewService.reviewStatusOf(merged.ordered()));
      }
      receipt.setSeenAt(null);
      log.info("Účtenka {} znovu rozpoznána (nahrazeno řádků: {}, ponecháno: {}, model {})", receiptId,
          merged.removed().size(), merged.ordered().size() - added.size(), job.getModel());
    } else {
      finish(receipt, ReceiptProcessingStatus.PROCESSED, now);
      log.info("Účtenka {} zpracována ({} řádků, model {})", receiptId, merged.ordered().size(), job.getModel());
    }
  }

  /**
   * Smaže nahrazené řádky a přečísluje výsledné pořadí. {@code uq_receipt_line_no} není
   * deferrable a Hibernate při flush dělá inserty před updaty a delety, proto po krocích:
   * smazat, ponechaným dočasně záporná čísla, pak finální čísla a vložení nových.
   */
  private void replaceLines(ReceiptLineMerger.Result merged) {
    lineRepository.deleteAll(merged.removed());
    lineRepository.flush();
    short temporary = -1;
    for (ReceiptLine line : merged.ordered()) {
      if (!line.isNew()) {
        line.setLineNo(temporary--);
      }
    }
    lineRepository.flush();
    short lineNo = 0;
    for (ReceiptLine line : merged.ordered()) {
      line.setLineNo(lineNo++);
    }
    lineRepository.saveAll(merged.ordered());
    lineRepository.flush();
  }

  @Transactional
  public void fail(Long receiptId, String error, OffsetDateTime leaseUntil) {
    ReceiptJob job = jobRepository.findForUpdateByReceiptId(receiptId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    if (job.getProcessedAt() != null) {
      // Výsledek už je uložený, jen worker nedostal odpověď na /result a hlásí chybu —
      // hotovou účtenku nesmí vrátit do fronty (processed_at by ji z lockAvailable vyřadil).
      log.warn("Chyba účtenky {} nahlášená k už ukončené úloze — ignoruje se", receiptId);
      return;
    }
    if (leaseUntil != null && (job.getLeaseUntil() == null || !leaseUntil.isEqual(job.getLeaseUntil()))) {
      // Hlášení ze staršího, mezitím vypršelého pronájmu — úlohu už má jiný pokus a ten by
      // chyba cizího pokusu vrátila do fronty uprostřed práce. Bez leaseUntil (starší worker)
      // platí jen kontrola dokončení výš.
      log.warn("Chyba účtenky {} z neplatného pronájmu — ignoruje se", receiptId);
      return;
    }
    job.setLeaseUntil(null);
    job.setLastError(truncate(error, 500));
    Receipt receipt = receiptRepository.findForUpdateById(receiptId).orElse(null);
    if (job.getAttempts() >= receiptProperties.getMaxAttempts()) {
      job.setProcessedAt(OffsetDateTime.now());
      if (receipt != null) {
        failUnlessReprocessing(receipt, OffsetDateTime.now());
      }
    } else if (receipt != null && !isReprocessing(receipt)) {
      receipt.setProcessingStatus(ReceiptProcessingStatus.QUEUED);
    }
  }

  /**
   * Obrázek pro worker — jen u účtenky, která je ve frontě (včetně nového rozpoznání, kdy
   * účtenka zůstává PROCESSED) nebo čeká na archivaci.
   */
  @Transactional(readOnly = true)
  public Optional<byte[]> image(Long receiptId, int seq) {
    Optional<Receipt> receipt = receiptRepository.findById(receiptId);
    boolean inQueue = receipt.isPresent()
        && jobRepository.findByReceiptId(receiptId).map(j -> j.getProcessedAt() == null).orElse(false);
    boolean forArchive = receipt.map(r -> r.isArchiveConsent() && r.getArchivedAt() == null).orElse(false);
    if (!inQueue && !forArchive) {
      return Optional.empty();
    }
    return receiptService.loadImage(receiptId, seq);
  }

  /** Kategorie s celou cestou česky (core.category.name je čeština, docs/lokalizace.md). */
  @Transactional(readOnly = true)
  public List<WorkerCategory> categories() {
    return categoryRepository.findAll().stream()
        .map(category -> new WorkerCategory(category.getSlug(), categoryPath(category)))
        .sorted(Comparator.comparing(WorkerCategory::path))
        .toList();
  }

  private static String categoryPath(Category category) {
    List<String> names = new ArrayList<>();
    for (Category current = category; current != null && names.size() < 10; current = current.getParent()) {
      names.addFirst(current.getName());
    }
    return String.join(" › ", names);
  }

  @Transactional(readOnly = true)
  public List<WorkerArchiveCandidate> archiveCandidates(Integer requested) {
    int limit = Math.max(1, Math.min(requested == null ? 20 : requested, 100));
    return receiptRepository.findArchiveCandidates(limit).stream()
        .map(r -> new WorkerArchiveCandidate(r.getId(),
            imagePaths(r.getId(), imageRepository.findByReceiptIdOrderBySeqAsc(r.getId()))))
        .toList();
  }

  @Transactional
  public void markArchived(Long receiptId) {
    Receipt receipt = receiptRepository.findById(receiptId)
        .filter(Receipt::isArchiveConsent)
        .orElseThrow(() -> new NotFoundException(ErrorCode.RECEIPT_NOT_FOUND));
    receipt.setArchivedAt(OffsetDateTime.now());
  }

  /**
   * Nové rozpoznání už zpracované účtenky: úloha je znovu ve frontě, účtenka ale zůstává
   * PROCESSED se starými řádky (docs/ai.md, „Nové rozpoznání").
   */
  private static boolean isReprocessing(Receipt receipt) {
    return receipt.getProcessingStatus() == ReceiptProcessingStatus.PROCESSED;
  }

  /** Selhání nového rozpoznání nechá platit původní výsledek; jinak účtenka končí FAILED. */
  private void failUnlessReprocessing(Receipt receipt, OffsetDateTime now) {
    if (isReprocessing(receipt)) {
      log.warn("Nové rozpoznání účtenky {} selhalo — ponechán původní výsledek", receipt.getId());
      return;
    }
    finish(receipt, ReceiptProcessingStatus.FAILED, now);
  }

  /**
   * Budoucí datum je chyba čtení (nebo špatné hodiny pokladny) — lepší prázdné pole, které
   * uživatel doplní, než cena zapsaná „zítra".
   */
  private static OffsetDateTime purchasedAt(WorkerReceiptResult result, OffsetDateTime now) {
    return result.purchasedAt() != null && result.purchasedAt().isAfter(now.plusHours(1)) ? null : result.purchasedAt();
  }

  private static String currency(String value) {
    return value != null && value.matches("[A-Z]{3}") ? value : null;
  }

  private static <T> T firstNonNull(T value, T fallback) {
    return value != null ? value : fallback;
  }

  private void finish(Receipt receipt, ReceiptProcessingStatus status, OffsetDateTime now) {
    receipt.setProcessingStatus(status);
    receipt.setProcessedAt(now);
    receipt.setSeenAt(null);
  }

  /** Relativní cesty — worker si před ně dá vlastní base URL (za proxy server svou adresu nezná). */
  private static List<String> imagePaths(Long receiptId, List<ReceiptImage> images) {
    return images.stream()
        .map(i -> "/api/worker/receipts/" + receiptId + "/images/" + i.getSeq())
        .toList();
  }

  private String toJson(Object extraction) {
    if (extraction == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(extraction);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static BigDecimal amount(BigDecimal value) {
    if (value == null || value.abs().compareTo(MAX_AMOUNT) > 0) {
      return null;
    }
    return value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal quantity(BigDecimal value) {
    if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("9999999")) > 0) {
      return null;
    }
    return value.setScale(3, java.math.RoundingMode.HALF_UP);
  }

  /**
   * Návrh kategorie a jednotky od modelu (ai.receipt_line_hint) — jen to, co projde kontrolou
   * i tady: existující kategorie, jednotka G/KG/ML/L, žádná jednotka u váhového zboží a žádný
   * rozpor s vodítkem gramáže z tisku (hmotnost proti objemu). Neplatná hodnota se zahodí,
   * nikdy neshodí výsledek účtenky (docs/ai.md, „Návrh kategorie a jednotky").
   */
  private void saveHints(List<ReceiptLine> lines, List<WorkerReceiptResult.Line> sources, String model) {
    List<ReceiptLineHint> hints = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      hintFor(lines.get(i).getId(), lines.get(i), sources.get(i), model).ifPresent(hints::add);
    }
    hintRepository.saveAll(hints);
  }

  /**
   * Nové rozpoznání beze změny řádků: stávající řádky dostanou gramáž z tisku a návrh modelu
   * z nové verze — včetně jejich zmizení, stejně jako by se řádky nahradily.
   *
   * @param targets stávající řádky (zůstávají, mění se jen vodítka)
   * @param fresh   odpovídající nové řádky (zdroj gramáže a kontrola jednotky v {@link #unitHint})
   */
  private void refreshGuidance(List<ReceiptLine> targets, List<ReceiptLine> fresh,
      List<WorkerReceiptResult.Line> sources, String model) {
    Map<Long, ReceiptLineHint> stored = new HashMap<>();
    hintRepository.findAllById(targets.stream().map(ReceiptLine::getId).toList())
        .forEach(h -> stored.put(h.getLineId(), h));
    for (int i = 0; i < targets.size(); i++) {
      ReceiptLine target = targets.get(i);
      ReceiptLine next = fresh.get(i);
      if (!Objects.equals(num(target.getNetContentHintValue()), num(next.getNetContentHintValue()))
          || target.getNetContentHintUom() != next.getNetContentHintUom()) {
        lineRepository.updateNetContentHint(target.getId(), next.getNetContentHintValue(), next.getNetContentHintUom());
      }
      Optional<ReceiptLineHint> hint = hintFor(target.getId(), next, sources.get(i), model);
      ReceiptLineHint old = stored.get(target.getId());
      if (old == null) {
        hint.ifPresent(hintRepository::save);
      } else if (hint.isEmpty()) {
        hintRepository.delete(old);
      } else {
        old.setCategoryId(hint.get().getCategoryId());
        old.setNetContentUom(hint.get().getNetContentUom());
        old.setModel(hint.get().getModel());
      }
    }
  }

  /** Návrh modelu k řádku, jen když z kontroly něco zbude ({@code line} nese gramáž z tisku). */
  private Optional<ReceiptLineHint> hintFor(Long lineId, ReceiptLine line, WorkerReceiptResult.Line in,
      String model) {
    String slug = in.categoryHintText();
    Long categoryId = slug == null || slug.isBlank() ? null
        : categoryRepository.findBySlug(slug.trim()).map(Category::getId).orElse(null);
    NetContentUom uom = unitHint(line, in.unitHintText());
    if (categoryId == null && uom == null) {
      return Optional.empty();
    }
    return Optional.of(ReceiptLineHint.builder().lineId(lineId).categoryId(categoryId).netContentUom(uom)
        .model(truncate(model, 100)).build());
  }

  private static BigDecimal num(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  static NetContentUom unitHint(ReceiptLine line, String value) {
    NetContentUom uom = enumOrNull(NetContentUom.class, value);
    if (uom == null || uom == NetContentUom.PCS || line.getQuantityUnit() != QuantityUnit.PCS) {
      return null;
    }
    NetContentUom printed = line.getNetContentHintUom();
    if (printed != null && isMass(printed) != isMass(uom)) {
      return null;
    }
    return uom;
  }

  private static boolean isMass(NetContentUom uom) {
    return uom == NetContentUom.G || uom == NetContentUom.KG;
  }

  /**
   * Vodítko gramáže z označení řádku — jen kladné číslo s jednotkou hmotnosti/objemu, jinak
   * nic. Hodnota a jednotka jdou vždy spolu (jako netContentValue/netContentUom u zboží).
   */
  private static NetContentUom netContentHint(WorkerReceiptResult.Line in) {
    NetContentUom uom = enumOrNull(NetContentUom.class, in.netContentUom());
    if (uom == null || uom == NetContentUom.PCS || in.netContentValue() == null
        || in.netContentValue().signum() <= 0 || in.netContentValue().compareTo(MAX_NET_CONTENT_HINT) >= 0) {
      return null;
    }
    return uom;
  }

  private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
