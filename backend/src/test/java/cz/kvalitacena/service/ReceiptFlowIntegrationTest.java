package cz.kvalitacena.service;

import cz.kvalitacena.controller.worker.WorkerReceiptJob;
import cz.kvalitacena.controller.worker.WorkerReceiptResult;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.EvidenceKind;
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
  }

  @Autowired private ReceiptService receiptService;
  @Autowired private ReceiptWorkerService workerService;
  @Autowired private ReceiptReviewService reviewService;
  @Autowired private ReceiptImageRetentionService retentionService;
  @Autowired private ReceiptMatchingService matchingService;
  @Autowired private PseudonymizationService pseudonymizationService;
  @Autowired private ReceiptRepository receiptRepository;
  @Autowired private ReceiptLineRepository lineRepository;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private StoreRepository storeRepository;
  @Autowired private RetailChainRepository chainRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

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
    // Nic není strojově spárované — obchod zatím nikdo neučil.
    assertThat(lines(receiptId)).allMatch(l -> l.getStatus() == ReceiptLineStatus.UNMATCHED);

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

  private ReceiptReviewService.ConfirmResult confirmSingle(AppUser owner, Fixture f, Long receiptId, Product product) {
    reviewService.updateHeader(owner.getPublicUid(), receiptId, f.store().getId(), null);
    ReceiptLine line = lines(receiptId).getFirst();
    reviewService.matchLine(owner.getPublicUid(), line.getId(), product.getId(), false);
    return reviewService.confirmLines(owner.getPublicUid(), receiptId, List.of(line.getId()), ObservationSource.WEB);
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
        new BigDecimal(total), discount == null ? null : new BigDecimal(discount), kind);
  }

  private List<ReceiptLine> lines(Long receiptId) {
    return lineRepository.findByReceiptIdOrderByLineNoAsc(receiptId);
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
