package cz.kvalitacena.service;

import cz.kvalitacena.controller.worker.WorkerReceiptJob;
import cz.kvalitacena.controller.worker.WorkerReceiptResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.EvidenceKind;
import cz.kvalitacena.db.entity.LabelDisplaySource;
import cz.kvalitacena.db.entity.NetContentUom;
import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.Receipt;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import cz.kvalitacena.db.entity.ReceiptProcessingStatus;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.entity.UnitBase;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Celý tok účtenky nad skutečným PostgreSQL (docs/datovy-model.md, „Účtenky"): nahrání →
 * lease workerem → výsledek → strojové párování → potvrzení → cena s RECEIPT_OCR, učení
 * označení a pozdější návrh u cizí účtenky. Generované sloupce (label_norm), výrazové unikátní
 * indexy a savepointy po řádcích Mockito neověří.
 */
@Testcontainers
@SpringBootTest
class ReceiptFlowIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @TempDir
  static Path mediaRoot;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
    registry.add("app.media.root", () -> mediaRoot.toString());
    // Testy nesmí volat skutečné Open Food Facts — snapshot si zakládají samy (offSnapshot).
    registry.add("app.external.open-food-facts.enabled", () -> false);
  }

  @Autowired private ReceiptService receiptService;
  @Autowired private ReceiptWorkerService workerService;
  @Autowired private ReceiptReviewService reviewService;
  @Autowired private ReceiptImageRetentionService retentionService;
  @Autowired private ReceiptMatchingService matchingService;
  @Autowired private LabelCurationService curationService;
  @Autowired private PseudonymizationService pseudonymizationService;
  @Autowired private ReceiptRepository receiptRepository;
  @Autowired private ReceiptLineRepository lineRepository;
  @Autowired private cz.kvalitacena.db.repo.ReceiptJobRepository jobRepository;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private StoreRepository storeRepository;
  @Autowired private RetailChainRepository chainRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private cz.kvalitacena.db.repo.OffProductRepository offProductRepository;
  @Autowired private NewProductDefaultsService productDefaultsService;
  @Autowired private cz.kvalitacena.controller.worker.WorkerReceiptController workerController;
  @Autowired private cz.kvalitacena.config.OpenFoodFactsProperties offProperties;
  // Skutečné Open Food Facts se nevolá nikdy — test obnovy cache si odpověď nastaví sám.
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private cz.kvalitacena.service.OpenFoodFactsApiClient offApiClient;

  @Test
  void fullFlowFromUploadToConfirmedPriceAndLearnedLabel() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("TUK.ROHLIK", "3", QuantityUnit.PCS, "2.90", "8.70", null, PriceKind.REGULAR),
        line("BANANY", "0.432", QuantityUnit.KG, "29.90", "12.92", null, PriceKind.REGULAR),
        line("MLEKO POLOTUC.", "1", QuantityUnit.PCS, "24.90", "24.90", "5.00", PriceKind.CLUB_CARD)));

    Receipt receipt = receiptRepository.findById(receiptId).orElseThrow();
    assertThat(receipt.getProcessingStatus()).isEqualTo(ReceiptProcessingStatus.PROCESSED);
    assertThat(receipt.getSuggestedStoreId()).isEqualTo(f.store().getId());
    // Žádné označení se zatím nikdo neučil. Rohlík a banány mají v katalogu řetězce jediného
    // podobného kandidáta, takže dostanou jen odhad (FUZZY), mléko nic.
    assertThat(lines(receiptId)).extracting(ReceiptLine::getMatchSource)
        .containsExactly(ReceiptMatchSource.FUZZY, ReceiptMatchSource.FUZZY, null);
    assertThat(lines(receiptId)).extracting(ReceiptLine::getProductId)
        .containsExactly(f.rohlik().getId(), f.banany().getId(), null);

    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    List<ReceiptLine> lines = lines(receiptId);
    reviewService.matchLine(owner.getPublicUid(), lines.get(0).getId(), f.rohlik().getId(), false);
    reviewService.matchLine(owner.getPublicUid(), lines.get(1).getId(), f.banany().getId(), false);
    reviewService.ignoreLine(owner.getPublicUid(), lines.get(2).getId());

    var result = reviewService.confirmLines(owner.getPublicUid(), receiptId,
        lines.stream().map(ReceiptLine::getId).toList(), ObservationSource.MOBILE);

    assertThat(result.confirmed()).isEqualTo(2);
    assertThat(result.skipped()).isEmpty();
    Map<String, Object> rohlik = jdbcTemplate.queryForMap(
        "SELECT price_amount, quantity_basis, evidence_kind, observed_at FROM core.price_observation WHERE product_id = ?",
        f.rohlik().getId());
    assertThat((BigDecimal) rohlik.get("price_amount")).isEqualByComparingTo("2.90");
    assertThat(rohlik.get("evidence_kind")).isEqualTo(EvidenceKind.RECEIPT_OCR.name());
    // Váhové zboží: cena za kg, nikdy zaplacená částka (docs/rozvoj.md).
    Map<String, Object> banany = jdbcTemplate.queryForMap(
        "SELECT price_amount, quantity_basis FROM core.price_observation WHERE product_id = ?", f.banany().getId());
    assertThat((BigDecimal) banany.get("price_amount")).isEqualByComparingTo("29.90");
    assertThat(banany.get("quantity_basis")).isEqualTo("PER_KG");
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getReviewStatus().name()).isEqualTo("DONE");

    // Naučené označení (práh 1) — druhá účtenka jiného uživatele ze stejného řetězce se spáruje sama.
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM core.product_store_label WHERE product_id = ? AND chain_id = ?",
        String.class, f.rohlik().getId(), f.chain().getId())).isEqualTo("ACTIVE");
    AppUser other = persistUser();
    Long second = uploadAndProcess(other, f, List.of(
        line("TUK. ROHLIK", "1", QuantityUnit.PCS, "2.90", "2.90", null, PriceKind.REGULAR)));
    ReceiptLine auto = lines(second).getFirst();
    assertThat(auto.getStatus()).isEqualTo(ReceiptLineStatus.SUGGESTED);
    assertThat(auto.getMatchSource()).isEqualTo(ReceiptMatchSource.LABEL);
    assertThat(auto.getProductId()).isEqualTo(f.rohlik().getId());
  }

  @Test
  void priceCurrencyFollowsStoreNotWorkerAssumption() {
    // Worker zatím čte jen české účtenky a CZK posílá napevno (tools/uctenky/profiles). Cena
    // ze slovenského obchodu se přesto musí zapsat v eurech, ne jako stejné číslo v Kč.
    Fixture f = fixture();
    Store slovak = storeRepository.saveAndFlush(Store.builder()
        .name("Predajňa " + f.ico()).street("Hlavná 1").city("Bratislava").country("SK").chain(f.chain())
        .status(StoreStatus.ACTIVE).build());
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "0.15", "0.15", null, PriceKind.REGULAR)));
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getCurrency()).isEqualTo("CZK");

    reviewService.updateHeader(owner.getPublicUid(), receiptId, slovak.getId(), null);
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getCurrency()).isEqualTo("EUR");
    ReceiptLine line = lines(receiptId).getFirst();
    reviewService.matchLine(owner.getPublicUid(), line.getId(), f.rohlik().getId(), false);
    reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(line.getId()), ObservationSource.WEB);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT currency FROM core.price_observation WHERE product_id = ? AND store_id = ?",
        String.class, f.rohlik().getId(), slovak.getId())).isEqualTo("EUR");

    // Nové rozpoznání měnu potvrzeného obchodu nepřepíše.
    workerService.requeue(List.of(receiptId), null);
    workerService.lease(10);
    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model-2", "raw2", null, null, null, null, null, new BigDecimal("0.15"), "CZK", List.of(
            line("ROHLIK", "1", QuantityUnit.PCS, "0.15", "0.15", null, PriceKind.REGULAR))));
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getCurrency()).isEqualTo("EUR");
  }

  @Test
  void duplicateSameDayIsSkippedWithoutFailingOtherLines() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long first = uploadAndProcess(owner, f, List.of(
        line("ROHLIK A", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    reviewService.updateHeader(owner.getPublicUid(), first, f.store().getId(), null);
    ReceiptLine l1 = lines(first).getFirst();
    reviewService.matchLine(owner.getPublicUid(), l1.getId(), f.rohlik().getId(), false);
    reviewService.confirmLines(owner.getPublicUid(), first, List.of(l1.getId()), ObservationSource.WEB);

    Long second = uploadAndProcess(owner, f, List.of(
        line("ROHLIK B", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR),
        line("BANAN", "1", QuantityUnit.KG, "29.90", "29.90", null, PriceKind.REGULAR)));
    reviewService.updateHeader(owner.getPublicUid(), second, f.store().getId(), null);
    List<ReceiptLine> lines = lines(second);
    reviewService.matchLine(owner.getPublicUid(), lines.get(0).getId(), f.rohlik().getId(), false);
    reviewService.matchLine(owner.getPublicUid(), lines.get(1).getId(), f.banany().getId(), false);

    var result = reviewService.confirmLines(owner.getPublicUid(), second,
        lines.stream().map(ReceiptLine::getId).toList(), ObservationSource.WEB);

    assertThat(result.confirmed()).isEqualTo(1);
    assertThat(result.skipped()).singleElement()
        .satisfies(l -> assertThat(l.getSkipReason()).isEqualTo(ReceiptReviewService.SKIP_DUPLICATE));
  }

  @Test
  void notAReceiptEndsWithoutLinesAndForeignOwnerSeesNothing() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, null);
    workerService.lease(10);
    workerService.submitResult(receipt.getId(), new WorkerReceiptResult(ReceiptProcessingStatus.NOT_A_RECEIPT,
        "test", "kočka", null, null, null, null, null, null, null, List.of()));

    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.NOT_A_RECEIPT);
    AppUser stranger = persistUser();
    assertThat(receiptService.ownerImage(stranger.getPublicUid(), receipt.getId(), 0)).isEmpty();
    assertThat(receiptService.ownerImage(owner.getPublicUid(), receipt.getId(), 0)).isPresent();
    assertThat(f).isNotNull();
  }

  @Test
  void uploadRequiresCurrentConsentVersion() {
    AppUser owner = persistUser();
    assertThatThrownBy(() -> receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 0, false, null, null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void retentionDeletesImagesButKeepsLines() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    jdbcTemplate.update("UPDATE core.receipt SET processed_at = now() - interval '40 days' WHERE id = ?", receiptId);

    retentionService.deleteExpiredImages();

    assertThat(receiptRepository.findById(receiptId).orElseThrow().getImagesDeletedAt()).isNotNull();
    assertThat(receiptService.ownerImage(owner.getPublicUid(), receiptId, 0)).isEmpty();
    assertThat(lines(receiptId)).hasSize(1);
  }

  @Test
  void ownMatchingDoesNotMarkReceiptAsUnseen() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    receiptService.markSeen(owner.getPublicUid(), receiptId);

    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    reviewService.matchLine(owner.getPublicUid(), lines(receiptId).getFirst().getId(), f.rohlik().getId(), false);

    // Novinka je jen návrh od jiných lidí — vlastní úprava odznak ani notifikaci nespouští.
    assertThat(receiptRepository.countUnseen(owner.getId())).isZero();
  }

  @Test
  void discountedLineRecordsPromoEvenWhenRegularWasRecordedToday() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long first = uploadAndProcess(owner, f, List.of(
        line("MLEKO", "1", QuantityUnit.PCS, "24.90", "24.90", null, PriceKind.REGULAR)));
    confirmSingle(owner, f, first, f.rohlik());

    Long second = uploadAndProcess(owner, f, List.of(
        line("MLEKO", "1", QuantityUnit.PCS, "24.90", "24.90", "5.00", PriceKind.CLUB_CARD)));
    var result = confirmSingle(owner, f, second, f.rohlik());

    // Běžná cena už dnes zapsaná je — vynechá se, klubová cena po slevě se ale zapsat musí.
    assertThat(result.confirmed()).isEqualTo(1);
    assertThat(result.skipped()).isEmpty();
    assertThat(jdbcTemplate.queryForList(
        "SELECT price_kind FROM core.price_observation WHERE product_id = ? ORDER BY price_kind",
        String.class, f.rohlik().getId())).containsExactly("CLUB_CARD", "REGULAR");
    assertThat(lines(second).getFirst().isConfirmed()).isTrue();
  }

  @Test
  void unknownLineIdFailsBeforeAnyPriceIsRecorded() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    ReceiptLine line = lines(receiptId).getFirst();
    reviewService.matchLine(owner.getPublicUid(), line.getId(), f.rohlik().getId(), false);

    assertThatThrownBy(() -> reviewService.confirmLines(owner.getPublicUid(), receiptId,
        List.of(line.getId(), Long.MAX_VALUE), ObservationSource.WEB))
        .isInstanceOf(NotFoundException.class);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.price_observation WHERE product_id = ?", Long.class, f.rohlik().getId())).isZero();
  }

  @Test
  void jobWhoseLeaseExpiredTooOftenEndsAsFailed() {
    AppUser owner = persistUser();
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, null);
    // Worker na ní třikrát spadl bez /fail — lease vypršel, pokusy jsou vyčerpané.
    jdbcTemplate.update("UPDATE ai.receipt_job SET attempts = 3, lease_until = now() - interval '1 minute' "
        + "WHERE receipt_id = ?", receipt.getId());

    List<WorkerReceiptJob> leased = workerService.lease(10);

    assertThat(leased).noneMatch(j -> j.receiptId().equals(receipt.getId()));
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.FAILED);
  }

  @Test
  void lateResultOrFailureDoesNotTouchProcessedReceipt() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    Long lineId = lines(receiptId).getFirst().getId();

    // Worker nedostal odpověď na /result: nahlásí chybu a pak výsledek pošle znovu.
    workerService.fail(receiptId, "timeout", null);
    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model", "raw", null, null, null, null, null, null, "CZK", List.of(
            line("JINY", "1", QuantityUnit.PCS, "9.00", "9.00", null, PriceKind.REGULAR),
            line("DALSI", "1", QuantityUnit.PCS, "1.00", "1.00", null, PriceKind.REGULAR))));

    assertThat(receiptRepository.findById(receiptId).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSED);
    assertThat(lines(receiptId)).singleElement().satisfies(l -> assertThat(l.getId()).isEqualTo(lineId));
  }

  @Test
  void failFromExpiredLeaseIsIgnoredButCurrentLeaseFails() {
    AppUser owner = persistUser();
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, null);
    WorkerReceiptJob job = workerService.lease(10).stream()
        .filter(j -> j.receiptId().equals(receipt.getId())).findFirst().orElseThrow();

    // Opožděné hlášení z dřívějšího pronájmu nesmí shodit ten, na kterém se právě pracuje.
    workerService.fail(receipt.getId(), "z dřívějšího pronájmu", job.leaseUntil().minusMinutes(15));
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSING);

    // Lease prošel JSONem do workeru a zpět — porovnává se okamžik, ne zápis s offsetem.
    workerService.fail(receipt.getId(), "OCR spadlo", job.leaseUntil().withOffsetSameInstant(ZoneOffset.UTC));
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.QUEUED);
  }

  @Test
  void pseudonymizationCutsReceiptLinkButLineStaysLocked() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    confirmSingle(owner, f, receiptId, f.rohlik());
    jdbcTemplate.update("UPDATE core.price_observation SET observed_at = now() - interval '200 days' "
        + "WHERE product_id = ?", f.rohlik().getId());

    pseudonymizationService.pseudonymizeOldObservations();

    // Účtenka drží vlastníka po celý život účtu — vazba cena ↔ řádek musí zmizet v obou směrech.
    assertThat(jdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.price_observation WHERE product_id = ? AND receipt_line_id IS NOT NULL",
        Long.class, f.rohlik().getId())).isZero();
    ReceiptLine line = lines(receiptId).getFirst();
    assertThat(line.getObservationId()).isNull();
    assertThat(line.isConfirmed()).isTrue();
    assertThatThrownBy(() -> reviewService.unmatchLine(owner.getPublicUid(), line.getId()))
        .isInstanceOf(ValidationException.class);
    assertThat(reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(line.getId()),
        ObservationSource.WEB).confirmed()).isZero();
  }

  @Test
  void repeatedUploadWithSameClientIdReturnsSameReceipt() {
    AppUser owner = persistUser();
    UUID clientUploadId = UUID.randomUUID();

    Receipt first = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, clientUploadId);
    Receipt again = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, clientUploadId);

    assertThat(again.getId()).isEqualTo(first.getId());
    assertThat(receiptRepository.countByOwnerUserId(owner.getId())).isEqualTo(1);
  }

  @Test
  void typedQueryWinsOverSuggestionsFromReceiptLabel() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long first = uploadAndProcess(owner, f, List.of(
        line("TUK.ROHLIK", "1", QuantityUnit.PCS, "2.90", "2.90", null, PriceKind.REGULAR)));
    confirmSingle(owner, f, first, f.rohlik());
    AppUser other = persistUser();
    Long second = uploadAndProcess(other, f, List.of(
        line("TUK.ROHLIK", "1", QuantityUnit.PCS, "2.90", "2.90", null, PriceKind.REGULAR)));

    List<Product> found = matchingService.suggestions(lines(second).getFirst(), f.store(),
        f.banany().getName(), null, 1);

    assertThat(found).extracting(Product::getId).containsExactly(f.banany().getId());
  }

  @Test
  void reprocessingKeepsConfirmedLineAndReplacesTheRest() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR),
        line("XYZ 5OOG", "1", QuantityUnit.PCS, "9.00", "9.00", null, PriceKind.REGULAR)));
    OffsetDateTime purchasedAt = receiptRepository.findById(receiptId).orElseThrow().getPurchasedAt();
    confirmSingle(owner, f, receiptId, f.rohlik());
    ReceiptLine confirmed = lines(receiptId).getFirst();
    Long replacedId = lines(receiptId).get(1).getId();
    assertThat(confirmed.isConfirmed()).isTrue();

    var requeued = workerService.requeue(List.of(receiptId), null);

    assertThat(requeued.requeued()).containsExactly(receiptId);
    // Vlastník s účtenkou pracuje dál, worker na obrázky dosáhne.
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSED);
    assertThat(workerService.image(receiptId, 0)).isPresent();
    assertThat(workerService.lease(10)).anySatisfy(j -> {
      assertThat(j.receiptId()).isEqualTo(receiptId);
      assertThat(j.imagePaths()).hasSize(2);
      assertThat(j.rawText()).isNull();
    });
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSED);

    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model-2", "raw2", null, f.store().getName(), null, null, null, new BigDecimal("12.00"), "CZK", List.of(
            line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR),
            line("XYZ 500G", "1", QuantityUnit.PCS, "9.00", "9.00", null, PriceKind.REGULAR),
            line("NOVA POLOZKA", "1", QuantityUnit.PCS, "4.00", "4.00", null, PriceKind.REGULAR))));

    List<ReceiptLine> after = lines(receiptId);
    assertThat(after).extracting(ReceiptLine::getLabelRaw).containsExactly("ROHLIK", "XYZ 500G", "NOVA POLOZKA");
    assertThat(after.getFirst().getId()).isEqualTo(confirmed.getId());
    assertThat(after.getFirst().getObservationId()).isEqualTo(confirmed.getObservationId());
    assertThat(after).extracting(ReceiptLine::getId).doesNotContain(replacedId);
    Receipt receipt = receiptRepository.findById(receiptId).orElseThrow();
    // Datum nákupu (patička) v novém výsledku chybí — zůstává původní, obchod potvrzený uživatelem taky.
    assertThat(receipt.getPurchasedAt()).isEqualTo(purchasedAt);
    assertThat(receipt.getStoreId()).isEqualTo(f.store().getId());
    assertThat(receipt.getStoreAddressRaw()).isEqualTo("Hlavní 1, Praha");
    assertThat(receipt.getTotalAmount()).isEqualByComparingTo("12.00");
    assertThat(receipt.getReviewStatus().name()).isEqualTo("IN_PROGRESS");
    assertThat(receipt.getSeenAt()).isNull();
    assertThat(workerService.image(receiptId, 0)).isEmpty();
  }

  @Test
  void reprocessingAfterRetentionUsesStoredTextAndWorseResultChangesNothing() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR)));
    Long lineId = lines(receiptId).getFirst().getId();
    jdbcTemplate.update("UPDATE core.receipt SET processed_at = now() - interval '40 days' WHERE id = ?", receiptId);
    retentionService.deleteExpiredImages();

    assertThat(workerService.requeue(List.of(receiptId), null).requeued()).containsExactly(receiptId);
    assertThat(workerService.lease(10)).anySatisfy(j -> {
      assertThat(j.receiptId()).isEqualTo(receiptId);
      assertThat(j.imagePaths()).isEmpty();
      assertThat(j.rawText()).isEqualTo("raw");
    });
    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.UNREADABLE,
        null, null, null, null, null, null, null, null, null, List.of()));

    assertThat(receiptRepository.findById(receiptId).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSED);
    assertThat(lines(receiptId)).extracting(ReceiptLine::getId).containsExactly(lineId);
    assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM ai.receipt_job WHERE receipt_id = ?",
        String.class, receiptId)).contains("UNREADABLE");

    // Stejný výsledek podruhé: řádky se nemění a účtenka se vlastníkovi nerozsvítí.
    receiptService.markSeen(owner.getPublicUid(), receiptId);
    workerService.requeue(List.of(receiptId), null);
    workerService.lease(10);
    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        null, null, null, null, null, null, null, null, null, List.of(
            line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR))));

    assertThat(lines(receiptId)).extracting(ReceiptLine::getId).containsExactly(lineId);
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getSeenAt()).isNotNull();
    assertThat(jdbcTemplate.queryForObject("SELECT raw_text FROM ai.receipt_job WHERE receipt_id = ?",
        String.class, receiptId)).isEqualTo("raw");
  }

  @Test
  void reprocessingWithSameLinesRefreshesNetContentAndModelHints() {
    Fixture f = fixture();
    String slug = f.rohlik().getCategory().getSlug();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(hinted("TESTOV.", "Těstoviny", null, null, null, null)));
    Long lineId = lines(receiptId).getFirst().getId();
    receiptService.markSeen(owner.getPublicUid(), receiptId);
    OffsetDateTime lineUpdatedAt = lineUpdatedAt(lineId);

    // Nová verze přečte gramáž z tisku a model navrhne kategorii — řádky jinak stejné.
    reprocess(receiptId, f, List.of(hinted("TESTOV.", "Těstoviny", "500", "G", slug, null)));

    ReceiptLine line = lines(receiptId).getFirst();
    assertThat(line.getId()).isEqualTo(lineId);
    // Vlastníkovi se nic viditelného nezměnilo: účtenka se nerozsvítí (seen_at ani updated_at řádku).
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getSeenAt()).isNotNull();
    assertThat(lineUpdatedAt(lineId)).isEqualTo(lineUpdatedAt);
    var refreshed = productDefaultsService.forLine(line, f.store().getId(), owner.getId());
    assertThat(refreshed.netContentValue()).isEqualByComparingTo("500");
    assertThat(refreshed.netContentUom()).isEqualTo(NetContentUom.G);
    assertThat(refreshed.netContentSource()).isEqualTo(NewProductDefaultsService.Source.RECEIPT);
    assertThat(refreshed.category().getSlug()).isEqualTo(slug);
    assertThat(refreshed.categorySource()).isEqualTo(NewProductDefaultsService.Source.AI);

    // Další verze gramáž z tisku nepřečte a model navrhne jen jednotku — stará vodítka neplatí.
    reprocess(receiptId, f, List.of(hinted("TESTOV.", "Těstoviny", null, null, null, "KG")));

    var unitOnly = productDefaultsService.forLine(lines(receiptId).getFirst(), f.store().getId(), owner.getId());
    assertThat(unitOnly.netContentValue()).isNull();
    assertThat(unitOnly.netContentUom()).isEqualTo(NetContentUom.KG);
    assertThat(unitOnly.netContentSource()).isEqualTo(NewProductDefaultsService.Source.AI);
    assertThat(unitOnly.category()).isNull();

    // Bez vodítek zmizí i návrh modelu.
    reprocess(receiptId, f, List.of(hinted("TESTOV.", "Těstoviny", null, null, null, null)));

    assertThat(lines(receiptId)).extracting(ReceiptLine::getId).containsExactly(lineId);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai.receipt_line_hint WHERE line_id = ?",
        Long.class, lineId)).isZero();
    assertThat(lineUpdatedAt(lineId)).isEqualTo(lineUpdatedAt);
  }

  @Test
  void requeuedFailedReceiptIsProcessedFromScratch() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null, null);
    jdbcTemplate.update("UPDATE ai.receipt_job SET attempts = 3, lease_until = now() - interval '1 minute' "
        + "WHERE receipt_id = ?", receipt.getId());
    workerService.lease(10);
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.FAILED);

    var first = workerService.requeue(List.of(receipt.getId(), Long.MAX_VALUE), null);
    var second = workerService.requeue(List.of(receipt.getId()), null);

    assertThat(first.requeued()).containsExactly(receipt.getId());
    assertThat(first.skipped()).extracting(ReceiptWorkerService.RequeueResult.Skipped::reason).containsExactly("not found");
    assertThat(second.skipped()).extracting(ReceiptWorkerService.RequeueResult.Skipped::reason)
        .containsExactly("already queued");
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.QUEUED);

    assertThat(workerService.lease(10)).anySatisfy(j -> assertThat(j.receiptId()).isEqualTo(receipt.getId()));
    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSING);
    workerService.submitResult(receipt.getId(), new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model", "raw", null, f.store().getName(), null, "CZ" + f.ico(), null, null, "CZK", List.of(
            line("ROHLIK", "1", QuantityUnit.PCS, "3.00", "3.00", null, PriceKind.REGULAR))));

    assertThat(receiptRepository.findById(receipt.getId()).orElseThrow().getProcessingStatus())
        .isEqualTo(ReceiptProcessingStatus.PROCESSED);
    assertThat(lines(receipt.getId())).hasSize(1);
    assertThat(jobRepository.findFinishedReceiptIdsSince(OffsetDateTime.now().minusMinutes(5), 500))
        .contains(receipt.getId());
  }

  private ReceiptReviewService.ConfirmResult confirmSingle(AppUser owner, Fixture f, Long receiptId, Product product) {
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    ReceiptLine line = lines(receiptId).getFirst();
    reviewService.matchLine(owner.getPublicUid(), line.getId(), product.getId(), false);
    return reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(line.getId()), ObservationSource.WEB);
  }

  @Test
  void confirmedLineTeachesEanToProductWithoutCodeAndNextReceiptMatchesByCode() {
    Fixture f = fixture();
    Product okurky = packaged(f, "Okurky");
    String ean = randomEan13();
    AppUser owner = persistUser();
    Long first = uploadAndProcess(owner, f, List.of(lineWithCode("NAKL. OKURKY", ean)));
    reviewService.updateHeader(owner.getPublicUid(), first, f.store().getId(), null);
    ReceiptLine line = lines(first).getFirst();
    reviewService.matchLine(owner.getPublicUid(), line.getId(), okurky.getId(), false);
    reviewService.confirmLines(owner.getPublicUid(), first, List.of(line.getId()), ObservationSource.WEB);

    assertThat(gtinsOf(okurky)).containsExactly("0" + ean);

    // Jiný uživatel, jiný text řádku — spáruje se podle EAN, ne podle naučeného označení.
    AppUser other = persistUser();
    Long second = uploadAndProcess(other, f, List.of(lineWithCode("OKURKY STERIL.", ean)));
    reviewService.updateHeader(other.getPublicUid(), second, f.store().getId(), null);
    ReceiptLine auto = lines(second).getFirst();
    assertThat(auto.getMatchSource()).isEqualTo(ReceiptMatchSource.CODE);
    assertThat(auto.getProductId()).isEqualTo(okurky.getId());
  }

  @Test
  void eanIsNotLearnedWhenProductHasOneOrCodeIsInvalidOrTaken() {
    Fixture f = fixture();
    Product withEan = packaged(f, "S kódem");
    String existing = randomEan13();
    jdbcTemplate.update("INSERT INTO core.product_code (product_id, code, code_type, is_primary) VALUES (?, ?, 'GTIN', true)",
        withEan.getId(), "0" + existing);
    Product withoutEan = packaged(f, "Bez kódu");
    String misread = randomEan13();
    misread = misread.substring(0, 12) + (char) ('0' + (misread.charAt(12) - '0' + 1) % 10);
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        lineWithCode("JINY EAN", randomEan13()),     // zboží už EAN má
        lineWithCode("SPATNE PRECTENY", misread),     // kontrolní číslice nesedí
        lineWithCode("CIZI EAN", existing),           // EAN patří jinému zboží
        lineWithCode("ROHLIK", randomEan13())));      // bezkódová položka
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    List<ReceiptLine> lines = lines(receiptId);
    reviewService.matchLine(owner.getPublicUid(), lines.get(0).getId(), withEan.getId(), false);
    reviewService.matchLine(owner.getPublicUid(), lines.get(1).getId(), withoutEan.getId(), false);
    reviewService.matchLine(owner.getPublicUid(), lines.get(2).getId(), withoutEan.getId(), false);
    reviewService.matchLine(owner.getPublicUid(), lines.get(3).getId(), f.rohlik().getId(), false);

    var result = reviewService.confirmLines(owner.getPublicUid(), receiptId,
        lines.stream().map(ReceiptLine::getId).toList(), ObservationSource.WEB);

    // Ceny se zapsaly všechny — nenaučený EAN potvrzení neshodí (druhý řádek k témuž zboží
    // je denní duplicita, ne chyba).
    assertThat(result.confirmed() + result.skipped().size()).isEqualTo(4);
    assertThat(result.skipped()).allSatisfy(l -> assertThat(l.getSkipReason()).isEqualTo(ReceiptReviewService.SKIP_DUPLICATE));
    assertThat(gtinsOf(withEan)).containsExactly("0" + existing);
    assertThat(gtinsOf(withoutEan)).isEmpty();
    assertThat(gtinsOf(f.rohlik())).isEmpty();
  }

  private List<String> gtinsOf(Product product) {
    return jdbcTemplate.queryForList(
        "SELECT code FROM core.product_code WHERE product_id = ? AND code_type = 'GTIN'", String.class, product.getId());
  }

  private Product packaged(Fixture f, String name) {
    return productRepository.saveAndFlush(Product.builder()
        .name(name + " " + UUID.randomUUID().toString().substring(0, 8)).category(f.rohlik().getCategory())
        .unitBase(UnitBase.COUNT).netContentBase(BigDecimal.ONE).status(ProductStatus.ACTIVE).build());
  }

  @Test
  void unambiguousNameSuggestsProductAsFuzzyAndStoresDisplayName() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Product nugeta = chainProduct(f, "Nugeta arašídová", new BigDecimal("340"), NetContentUom.G);
    Long receiptId = uploadAndProcess(owner, f, List.of(
        displayLine("NUGETA ARASÍDOVÁ340G", "Nugeta arašídová 340g", "340", "G", null),
        displayLine("NUGETA ARASÍDOVÁ400G", "Nugeta arašídová 400g", "400", "G", null)));

    List<ReceiptLine> lines = lines(receiptId);
    assertThat(lines.get(0).getLabelDisplay()).isEqualTo("Nugeta arašídová 340g");
    assertThat(lines.get(0).getLabelDisplaySource()).isEqualTo(LabelDisplaySource.RULES);
    assertThat(lines.get(0).getNetContentHintUom()).isEqualTo(NetContentUom.G);
    assertThat(lines.get(0).getStatus()).isEqualTo(ReceiptLineStatus.SUGGESTED);
    assertThat(lines.get(0).getMatchSource()).isEqualTo(ReceiptMatchSource.FUZZY);
    assertThat(lines.get(0).getProductId()).isEqualTo(nugeta.getId());
    // Jiná gramáž = jiné zboží (jiné balení), odhad by tiše zapsal cenu ke špatnému.
    assertThat(lines.get(1).getStatus()).isEqualTo(ReceiptLineStatus.UNMATCHED);
  }

  @Test
  void twoSimilarProductsGiveNoFuzzySuggestion() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    chainProduct(f, "Arašídy solené pražené", new BigDecimal("200"), NetContentUom.G);
    chainProduct(f, "Arašídy solené pražené", new BigDecimal("200"), NetContentUom.G);
    Long receiptId = uploadAndProcess(owner, f, List.of(
        displayLine("ARAŠ.SOL.PRAŽ.200G", "Arašídy solené pražené 200g", "200", "G", null)));

    assertThat(lines(receiptId).getFirst().getStatus()).isEqualTo(ReceiptLineStatus.UNMATCHED);
  }

  @Test
  void fuzzyWithEanTeachesCodeOnlyAfterConfirmationAndConflictingEanBlocksIt() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Product kesu = chainProduct(f, "Kešu ořechy natural", new BigDecimal("150"), NetContentUom.G);
    Product mandle = chainProduct(f, "Mandle loupané natural", new BigDecimal("100"), NetContentUom.G);
    jdbcTemplate.update("INSERT INTO core.product_code (product_id, code, code_type, is_primary) VALUES (?, ?, 'GTIN', true)",
        mandle.getId(), GtinNormalization.toGtin14(randomEan13()));
    String kesuEan = randomEan13();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        displayLine("KEŠU OŘ.NAT.150G", "Kešu ořechy natural 150g", "150", "G", kesuEan),
        displayLine("MANDLE LOUP.NAT.100G", "Mandle loupané natural 100g", "100", "G", randomEan13())));

    List<ReceiptLine> lines = lines(receiptId);
    assertThat(lines.get(0).getMatchSource()).isEqualTo(ReceiptMatchSource.FUZZY);
    // Zboží už EAN má a řádek nese jiný — rozpor, ne odhad.
    assertThat(lines.get(1).getStatus()).isEqualTo(ReceiptLineStatus.UNMATCHED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM core.product_code WHERE product_id = ?",
        Integer.class, kesu.getId())).isZero();

    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    assertThat(lines(receiptId).get(0).getMatchSource()).isEqualTo(ReceiptMatchSource.FUZZY);
    var result = reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(lines.get(0).getId()),
        ObservationSource.WEB);
    assertThat(result.confirmed()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT code FROM core.product_code WHERE product_id = ?",
        String.class, kesu.getId())).isEqualTo(GtinNormalization.toGtin14(kesuEan));
  }

  @Test
  void learnedLabelReplacesFuzzyGuessOnOtherReceipts() {
    Fixture f = fixture();
    AppUser first = persistUser();
    AppUser second = persistUser();
    Product guess = chainProduct(f, "Olma Selské mléko", new BigDecimal("1"), NetContentUom.L);
    Long otherReceipt = uploadAndProcess(second, f, List.of(
        displayLine("OLMA SELS.MLÉ.3,9%1L", "Olma Selské mléko 3,9% 1l", "1", "L", null)));
    assertThat(lines(otherReceipt).getFirst().getMatchSource()).isEqualTo(ReceiptMatchSource.FUZZY);

    Product right = chainProduct(f, "Plnotučné mléko 3,9 %", new BigDecimal("1"), NetContentUom.L);
    Long receiptId = uploadAndProcess(first, f, List.of(
        displayLine("OLMA SELS.MLÉ.3,9%1L", "Olma Selské mléko 3,9% 1l", "1", "L", null)));
    reviewService.updateHeader(first.getPublicUid(), receiptId, f.store().getId(), null);
    ReceiptLine line = lines(receiptId).getFirst();
    reviewService.matchLine(first.getPublicUid(), line.getId(), right.getId(), false);
    reviewService.confirmLines(first.getPublicUid(), receiptId, List.of(line.getId()), ObservationSource.WEB);

    ReceiptLine other = lines(otherReceipt).getFirst();
    assertThat(other.getMatchSource()).isEqualTo(ReceiptMatchSource.LABEL);
    assertThat(other.getProductId()).isEqualTo(right.getId()).isNotEqualTo(guess.getId());
  }

  @Test
  void operatorMappingIsActiveAtOnceSuggestsOtherReceiptsAndNeedsReplaceToOverride() {
    Fixture f = fixture();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        displayLine("GRAN MORAV.STR.100G", "Gran Moravia str. 100g", "100", "G", null),
        displayLine("GRAN MORAV.STR.100G", "Gran Moravia str. 100g", "100", "G", null)));
    assertThat(lines(receiptId)).allMatch(l -> l.getStatus() == ReceiptLineStatus.UNMATCHED);

    List<LabelCurationService.UnmatchedLabel> unmatched = curationService.unmatched(f.chain().getId(), 50);
    assertThat(unmatched).singleElement().satisfies(label -> {
      assertThat(label.label()).isEqualTo("GRAN MORAV.STR.100G");
      assertThat(label.labelDisplay()).isEqualTo("Gran Moravia str. 100g");
      assertThat(label.lineCount()).isEqualTo(2);
      assertThat(label.chainId()).isEqualTo(f.chain().getId());
      assertThat(label.sampleStoreId()).isEqualTo(f.store().getId());
    });

    Product gran = chainProduct(f, "Grana Padano strouhaný sýr", new BigDecimal("100"), NetContentUom.G);
    assertThat(curationService.searchProducts("Grana Padano", f.store().getId(), 5))
        .extracting(LabelCurationService.ProductCandidate::id).contains(gran.getId());
    var created = curationService.map(new LabelCurationService.LabelMappingRequest(
        "GRAN MORAV.STR.100G", f.chain().getId(), null, gran.getId(), false));
    assertThat(created.outcome()).isEqualTo(LabelCurationService.Outcome.CREATED);
    assertThat(created.suggestedLines()).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status || '/' || source FROM core.product_store_label WHERE product_id = ?", String.class, gran.getId()))
        .isEqualTo("ACTIVE/OPERATOR");
    assertThat(lines(receiptId)).allMatch(l -> l.getMatchSource() == ReceiptMatchSource.LABEL
        && gran.getId().equals(l.getProductId()));
    assertThat(curationService.unmatched(f.chain().getId(), 50)).isEmpty();

    // Oprava na jiné zboží — bez replace konflikt, s replace se přesměrují i nepotvrzené návrhy.
    Product moravia = chainProduct(f, "Gran Moravia strouhaný", new BigDecimal("100"), NetContentUom.G);
    var conflict = curationService.map(new LabelCurationService.LabelMappingRequest(
        "GRAN MORAV. STR. 100G", f.chain().getId(), null, moravia.getId(), false));
    assertThat(conflict.outcome()).isEqualTo(LabelCurationService.Outcome.CONFLICT);
    assertThat(conflict.existingProductId()).isEqualTo(gran.getId());
    var replaced = curationService.map(new LabelCurationService.LabelMappingRequest(
        "GRAN MORAV. STR. 100G", f.chain().getId(), null, moravia.getId(), true));
    assertThat(replaced.outcome()).isEqualTo(LabelCurationService.Outcome.UPDATED);
    assertThat(lines(receiptId)).allMatch(l -> moravia.getId().equals(l.getProductId()));

    // Uživatel, který řádek spáruje jinak, mapování provozovatele nepřepíše.
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    ReceiptLine first = lines(receiptId).getFirst();
    reviewService.matchLine(owner.getPublicUid(), first.getId(), gran.getId(), false);
    reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(first.getId()), ObservationSource.WEB);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT product_id FROM core.product_store_label WHERE label_norm = core.norm_label('GRAN MORAV.STR.100G') "
            + "AND chain_id = ?", Long.class, f.chain().getId())).isEqualTo(moravia.getId());
  }

  @Test
  void operatorMappingRejectsInvalidScope() {
    Fixture f = fixture();
    var both = curationService.map(new LabelCurationService.LabelMappingRequest(
        "ROHLIK", f.chain().getId(), f.store().getId(), f.rohlik().getId(), false));
    assertThat(both.outcome()).isEqualTo(LabelCurationService.Outcome.INVALID);
    var noProduct = curationService.map(new LabelCurationService.LabelMappingRequest(
        "ROHLIK", f.chain().getId(), null, -5L, false));
    assertThat(noProduct.outcome()).isEqualTo(LabelCurationService.Outcome.INVALID);
  }

  @Test
  void eanMatchesCatalogEvenWithoutKnownStore() {
    Fixture f = fixture();
    Product okurky = packaged(f, "Okurky bez obchodu");
    String ean = randomEan13();
    jdbcTemplate.update("INSERT INTO core.product_code (product_id, code, code_type, is_primary) VALUES (?, ?, 'GTIN', true)",
        okurky.getId(), "0" + ean);
    AppUser owner = persistUser();
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, OffsetDateTime.now(), null);
    workerService.lease(10);
    // Hlavička bez obchodu, který by šel navrhnout — EAN je globální a obchod nepotřebuje.
    workerService.submitResult(receipt.getId(), new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model", "raw", Map.of("k", "v"), null, null, null, OffsetDateTime.now().minusHours(2),
        new BigDecimal("44.90"), "CZK", List.of(lineWithCode("NAKL. OKURKY", ean))));

    ReceiptLine line = lines(receipt.getId()).getFirst();
    assertThat(line.getMatchSource()).isEqualTo(ReceiptMatchSource.CODE);
    assertThat(line.getProductId()).isEqualTo(okurky.getId());
  }

  @Test
  void eanKnownOnlyToOffIsSuggestedAndConfirmationCreatesProduct() {
    Fixture f = fixture();
    String ean = randomEan13();
    offSnapshot(ean, f.rohlik().getCategory().getSlug(), "500", "G", Map.of("cs", "Těstoviny vřetena"));
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        displayLine("TESTOV.VRETENA 500G", "Těstoviny vřetena 500g", "500", "G", ean)));
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);

    ReceiptLine line = lines(receiptId).getFirst();
    assertThat(line.getStatus()).isEqualTo(ReceiptLineStatus.SUGGESTED);
    assertThat(line.getMatchSource()).isEqualTo(ReceiptMatchSource.OFF);
    assertThat(line.getProductId()).isNull();
    assertThat(matchingService.offCandidate(line)).isPresent();

    var result = reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(line.getId()),
        ObservationSource.MOBILE);

    assertThat(result.confirmed()).isEqualTo(1);
    ReceiptLine confirmed = lines(receiptId).getFirst();
    assertThat(confirmed.getStatus()).isEqualTo(ReceiptLineStatus.MATCHED);
    assertThat(confirmed.getProductId()).isNotNull();
    assertThat(gtinsOf(productRepository.findById(confirmed.getProductId()).orElseThrow())).containsExactly("0" + ean);
    // ODbL: OFF hodnoty se do core.product nekopírují — název OFF má česky, takže zůstane NULL.
    Map<String, Object> stored = jdbcTemplate.queryForMap(
        "SELECT name, net_content_value, category_id FROM core.product WHERE id = ?", confirmed.getProductId());
    assertThat(stored.get("name")).isNull();
    assertThat(stored.get("net_content_value")).isNull();
    assertThat(stored.get("category_id")).isNull();
  }

  @Test
  void offProductWithoutCategoryOrNetContentStaysUnmatched() {
    Fixture f = fixture();
    String noCategory = randomEan13();
    offSnapshot(noCategory, null, "500", "G", Map.of("cs", "Bez kategorie"));
    String noContent = randomEan13();
    offSnapshot(noContent, f.rohlik().getCategory().getSlug(), null, null, Map.of("cs", "Bez gramáže"));
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        lineWithCode("BEZ KATEGORIE", noCategory), lineWithCode("BEZ GRAMAZE", noContent)));

    assertThat(lines(receiptId)).extracting(ReceiptLine::getStatus)
        .containsOnly(ReceiptLineStatus.UNMATCHED);
  }

  @Test
  void offSuggestionUsesProductCreatedMeanwhileWithSameEan() {
    Fixture f = fixture();
    String ean = randomEan13();
    offSnapshot(ean, f.rohlik().getCategory().getSlug(), "1", "L", Map.of("de", "Milch"));
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(lineWithCode("MLEKO 1L", ean)));
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    assertThat(lines(receiptId).getFirst().getMatchSource()).isEqualTo(ReceiptMatchSource.OFF);
    Product meanwhile = packaged(f, "Mléko mezitím");
    jdbcTemplate.update("INSERT INTO core.product_code (product_id, code, code_type, is_primary) VALUES (?, ?, 'GTIN', true)",
        meanwhile.getId(), "0" + ean);

    reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(lines(receiptId).getFirst().getId()),
        ObservationSource.WEB);

    assertThat(lines(receiptId).getFirst().getProductId()).isEqualTo(meanwhile.getId());
  }

  @Test
  void secondOffLineWithSameEanIsMatchedEvenWhenItsPriceIsDuplicate() {
    Fixture f = fixture();
    String ean = randomEan13();
    offSnapshot(ean, f.rohlik().getCategory().getSlug(), "500", "G", Map.of("cs", "Těstoviny kolínka"));
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        lineWithCode("TESTOV.KOLINKA", ean), lineWithCode("TESTOV.KOLINKA", ean)));
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    assertThat(lines(receiptId)).extracting(ReceiptLine::getMatchSource).containsOnly(ReceiptMatchSource.OFF);

    var result = reviewService.confirmLines(owner.getPublicUid(), receiptId,
        lines(receiptId).stream().map(ReceiptLine::getId).toList(), ObservationSource.WEB);

    assertThat(result.confirmed()).isEqualTo(1);
    assertThat(result.skipped()).hasSize(1);
    // Druhá cena téhož druhu týž den je duplicita, párování ale platí — jinak by řádek zůstal
    // navždy SUGGESTED (doplněné zboží zmizelo s rollbackem transakce řádku) a účtenka rozpracovaná.
    List<ReceiptLine> after = lines(receiptId);
    assertThat(after).extracting(ReceiptLine::getStatus).containsOnly(ReceiptLineStatus.MATCHED);
    assertThat(after).extracting(ReceiptLine::getProductId).doesNotContainNull().containsOnly(after.getFirst().getProductId());
    assertThat(receiptRepository.findById(receiptId).orElseThrow().getReviewStatus().name()).isEqualTo("DONE");
  }

  @Test
  void offCandidateOutsideTransactionStoresRefreshedSnapshot() {
    Fixture f = fixture();
    String ean = randomEan13();
    offSnapshot(ean, f.rohlik().getCategory().getSlug(), "500", "G", Map.of("cs", "Prošlý snapshot"));
    jdbcTemplate.update("UPDATE off.product SET fetched_at = now() - interval '400 days' WHERE gtin = ?", "0" + ean);
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(lineWithCode("PROSLY", ean)));
    ReceiptLine line = lines(receiptId).getFirst();
    org.mockito.Mockito.when(offApiClient.fetch(ean)).thenReturn(java.util.Optional.empty());
    offProperties.setEnabled(true);
    try {
      // Stejně jako resolver ReceiptLine.offCandidate: mimo transakci volajícího.
      assertThat(matchingService.offCandidate(line)).isEmpty();
    } finally {
      offProperties.setEnabled(false);
    }

    assertThat(jdbcTemplate.queryForObject("SELECT fetch_status FROM off.product WHERE gtin = ?", String.class, "0" + ean))
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void modelHintsAreValidatedAndFillNewProductDefaultsAfterReceiptData() {
    Fixture f = fixture();
    String slug = f.rohlik().getCategory().getSlug();
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        // Model: kategorie i objem — vytištěná gramáž (500 g) má přednost a rozporná jednotka se zahodí.
        hinted("TESTOV. 500G", "Těstoviny 500g", "500", "G", slug, "L"),
        // Jen model: kategorie a jednotka bez čísla.
        hinted("MLEKO POL.", "Mléko pol.", null, null, slug, "L"),
        // Neexistující kategorie a nesmyslná jednotka se zahodí.
        hinted("NECO", "Něco", null, null, "neexistuje-" + UUID.randomUUID(), "KUS"),
        // Hodnota jiného typu než text se zahodí a výsledek účtenky neshodí.
        new WorkerReceiptResult.Line("JINE", null, BigDecimal.ONE, QuantityUnit.PCS, new BigDecimal("9.90"),
            new BigDecimal("9.90"), null, PriceKind.REGULAR, "Jiné", "RULES", null, null, List.of(slug),
            Map.of("unit", "L")),
        // Váhové zboží jednotku od modelu nedostane.
        new WorkerReceiptResult.Line("BANANY", null, new BigDecimal("0.4"), QuantityUnit.KG, new BigDecimal("29.90"),
            new BigDecimal("11.96"), null, PriceKind.REGULAR, "Banány", "RULES", null, null, null, "G")));
    List<ReceiptLine> lines = lines(receiptId);

    var pasta = productDefaultsService.forLine(lines.get(0), f.store().getId(), owner.getId());
    assertThat(pasta.netContentValue()).isEqualByComparingTo("500");
    assertThat(pasta.netContentUom()).isEqualTo(NetContentUom.G);
    assertThat(pasta.netContentSource()).isEqualTo(NewProductDefaultsService.Source.RECEIPT);
    assertThat(pasta.name()).isEqualTo("Těstoviny 500g");

    var milk = productDefaultsService.forLine(lines.get(1), f.store().getId(), owner.getId());
    assertThat(milk.netContentValue()).isNull();
    assertThat(milk.netContentUom()).isEqualTo(NetContentUom.L);
    assertThat(milk.netContentSource()).isEqualTo(NewProductDefaultsService.Source.AI);
    assertThat(milk.category().getSlug()).isEqualTo(slug);
    assertThat(milk.categorySource()).isEqualTo(NewProductDefaultsService.Source.AI);

    var nothing = productDefaultsService.forLine(lines.get(2), f.store().getId(), owner.getId());
    assertThat(nothing.category()).isNull();
    assertThat(nothing.netContentUom()).isNull();

    var other = productDefaultsService.forLine(lines.get(3), f.store().getId(), owner.getId());
    assertThat(other.category()).isNull();
    assertThat(other.netContentUom()).isNull();

    var bananas = productDefaultsService.forLine(lines.get(4), f.store().getId(), owner.getId());
    assertThat(bananas.isVariableWeight()).isTrue();
    assertThat(bananas.netContentUom()).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai.receipt_line_hint WHERE line_id = ?",
        Long.class, lines.get(4).getId())).isZero();
  }

  @Test
  void catalogNeighboursAgreeingOnCategoryWinOverModel() {
    Fixture f = fixture();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Category sirup = categoryRepository.saveAndFlush(Category.builder()
        .name("Sirupy " + suffix).slug("sirupy-" + suffix).path("sirupy-" + suffix).build());
    for (String flavour : List.of("malinový", "jahodový", "citronový")) {
      productRepository.saveAndFlush(Product.builder().name("Sirup zbrkl " + flavour + " " + suffix).category(sirup)
          .unitBase(UnitBase.COUNT).netContentBase(BigDecimal.ONE).status(ProductStatus.ACTIVE).build());
    }
    AppUser owner = persistUser();
    Long receiptId = uploadAndProcess(owner, f, List.of(
        hinted("SIRUP ZBRKL MALIN", "Sirup zbrkl malinový " + suffix, null, null, f.rohlik().getCategory().getSlug(),
            null)));

    var defaults = productDefaultsService.forLine(lines(receiptId).getFirst(), f.store().getId(), owner.getId());
    assertThat(defaults.category().getId()).isEqualTo(sirup.getId());
    assertThat(defaults.categorySource()).isEqualTo(NewProductDefaultsService.Source.CATALOG);
  }

  @Test
  void workerSeesCategoriesWithFullPath() {
    Fixture f = fixture();
    assertThat(workerController.categories())
        .anySatisfy(c -> {
          assertThat(c.slug()).isEqualTo(f.rohlik().getCategory().getSlug());
          assertThat(c.path()).isEqualTo(f.rohlik().getCategory().getName());
        });
  }

  private static WorkerReceiptResult.Line hinted(String label, String display, String hintValue, String hintUom,
      String categoryHint, String unitHint) {
    return new WorkerReceiptResult.Line(label, null, BigDecimal.ONE, QuantityUnit.PCS, new BigDecimal("29.90"),
        new BigDecimal("29.90"), null, PriceKind.REGULAR, display, "RULES",
        hintValue == null ? null : new BigDecimal(hintValue), hintUom, categoryHint, unitHint);
  }

  private void offSnapshot(String ean, String categorySlug, String quantity, String unit, Map<String, String> names) {
    offProductRepository.saveAndFlush(cz.kvalitacena.db.entity.OffProduct.builder()
        .gtin("0" + ean).fetchStatus(cz.kvalitacena.db.entity.OffFetchStatus.FOUND)
        .names(new java.util.LinkedHashMap<>(names)).nameLocales(new java.util.ArrayList<>(List.of("cs", "sk", "en", "pl", "de")))
        .productQuantity(quantity == null ? null : new BigDecimal(quantity)).productQuantityUnit(unit)
        .mappedCategorySlug(categorySlug).fetchedAt(OffsetDateTime.now()).build());
  }

  private Product chainProduct(Fixture f, String name, BigDecimal value, NetContentUom uom) {
    UnitBase unitBase = uom == NetContentUom.G || uom == NetContentUom.KG ? UnitBase.MASS : UnitBase.VOLUME;
    BigDecimal base = uom == NetContentUom.G || uom == NetContentUom.ML
        ? value.divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP) : value;
    return productRepository.saveAndFlush(Product.builder()
        .name(name).category(f.rohlik().getCategory()).unitBase(unitBase).netContentValue(value).netContentUom(uom)
        .netContentBase(base).catalogScope(ProductScope.CHAIN).scopeChain(f.chain()).status(ProductStatus.ACTIVE)
        .build());
  }

  private static WorkerReceiptResult.Line displayLine(String label, String display, String hintValue, String hintUom,
      String code) {
    return new WorkerReceiptResult.Line(label, code, BigDecimal.ONE, QuantityUnit.PCS, new BigDecimal("49.90"),
        new BigDecimal("49.90"), null, PriceKind.REGULAR, display, "RULES",
        hintValue == null ? null : new BigDecimal(hintValue), hintUom);
  }

  /** Náhodný EAN-13 s platnou kontrolní číslicí a českým prefixem 859 (globální, ne omezený oběh). */
  private static String randomEan13() {
    String body = "859" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
    int sum = 0;
    for (int i = body.length() - 1, weight = 3; i >= 0; i--, weight = 4 - weight) {
      sum += (body.charAt(i) - '0') * weight;
    }
    return body + (10 - sum % 10) % 10;
  }

  private static WorkerReceiptResult.Line lineWithCode(String label, String code) {
    return new WorkerReceiptResult.Line(label, code, BigDecimal.ONE, QuantityUnit.PCS, new BigDecimal("44.90"),
        new BigDecimal("44.90"), null, PriceKind.REGULAR, null, null, null, null);
  }

  private Long uploadAndProcess(AppUser owner, Fixture f, List<WorkerReceiptResult.Line> lines) {
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg(), jpeg()), 1, false, OffsetDateTime.now(),
        null);
    List<WorkerReceiptJob> leased = workerService.lease(10);
    assertThat(leased).anySatisfy(j -> {
      assertThat(j.receiptId()).isEqualTo(receipt.getId());
      assertThat(j.imagePaths()).hasSize(2);
    });
    workerService.submitResult(receipt.getId(), new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model", "raw", Map.of("k", "v"), f.store().getName(), "Hlavní 1, Praha", "CZ" + f.ico(),
        OffsetDateTime.now().minusHours(2), new BigDecimal("46.52"), "CZK", lines));
    return receipt.getId();
  }

  private static WorkerReceiptResult.Line line(String label, String qty, QuantityUnit unit, String unitPrice,
      String total, String discount, PriceKind kind) {
    return new WorkerReceiptResult.Line(label, null, new BigDecimal(qty), unit, new BigDecimal(unitPrice),
        new BigDecimal(total), discount == null ? null : new BigDecimal(discount), kind, null, null, null, null);
  }

  private List<ReceiptLine> lines(Long receiptId) {
    return lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
  }

  /** Nové rozpoznání: requeue → lease → výsledek s novými řádky (syrová hlavička bez patičky). */
  private void reprocess(Long receiptId, Fixture f, List<WorkerReceiptResult.Line> lines) {
    assertThat(workerService.requeue(List.of(receiptId), null).requeued()).containsExactly(receiptId);
    assertThat(workerService.lease(10)).anySatisfy(j -> assertThat(j.receiptId()).isEqualTo(receiptId));
    workerService.submitResult(receiptId, new WorkerReceiptResult(ReceiptProcessingStatus.PROCESSED,
        "test-model-2", "raw2", null, f.store().getName(), null, null, null, null, "CZK", lines));
  }

  private OffsetDateTime lineUpdatedAt(Long lineId) {
    return jdbcTemplate.queryForObject("SELECT updated_at FROM core.receipt_line WHERE id = ?",
        OffsetDateTime.class, lineId);
  }

  record Fixture(RetailChain chain, Store store, String ico, Product rohlik, Product banany) {
  }

  private Fixture fixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    RetailChain chain = chainRepository.saveAndFlush(RetailChain.builder()
        .name("Řetězec " + suffix).slug("retezec-" + suffix)
        .chainType(cz.kvalitacena.db.entity.ChainType.CHAIN).country("CZ").build());
    String ico = String.valueOf(10_000_000 + Math.abs(suffix.hashCode() % 89_999_999));
    Store store = storeRepository.saveAndFlush(Store.builder()
        .name("Prodejna " + suffix).street("Hlavní 1").city("Praha").country("CZ").chain(chain).ico(ico)
        .status(StoreStatus.ACTIVE).build());
    Category category = categoryRepository.saveAndFlush(Category.builder()
        .name("Účtenky " + suffix).slug("uctenky-" + suffix).path("uctenky-" + suffix).build());
    Product rohlik = productRepository.saveAndFlush(Product.builder()
        .name("Rohlík tukový " + suffix).category(category).unitBase(UnitBase.COUNT).netContentBase(BigDecimal.ONE)
        .generic(true).catalogScope(ProductScope.CHAIN).scopeChain(chain).status(ProductStatus.ACTIVE).build());
    Product banany = productRepository.saveAndFlush(Product.builder()
        .name("Banány " + suffix).category(category).unitBase(UnitBase.MASS).netContentBase(BigDecimal.ONE)
        .variableWeight(true).generic(true).catalogScope(ProductScope.CHAIN).scopeChain(chain)
        .status(ProductStatus.ACTIVE).build());
    return new Fixture(chain, store, ico, rohlik, banany);
  }

  private AppUser persistUser() {
    String unique = UUID.randomUUID().toString();
    return appUserRepository.saveAndFlush(AppUser.builder()
        .publicUid(UUID.randomUUID())
        .emailHash(unique.getBytes())
        .emailEnc(unique.getBytes())
        .publicHandle(unique.substring(0, 30))
        .handleAdjective("blue")
        .handleNoun("stork")
        .handleNumber((short) 1)
        .status(AppUserStatus.ACTIVE)
        .build());
  }

  private static byte[] jpeg() {
    BufferedImage image = new BufferedImage(120, 400, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 400; y += 20) {
      for (int x = 0; x < 120; x++) {
        image.setRGB(x, y, 0xFFFFFF);
      }
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
