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
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 1, false, null);
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
    assertThatThrownBy(() -> receiptService.upload(owner.getPublicUid(), List.of(jpeg()), 0, false, null))
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

  private Long uploadAndProcess(AppUser owner, Fixture f, List<WorkerReceiptResult.Line> lines) {
    Receipt receipt = receiptService.upload(owner.getPublicUid(), List.of(jpeg(), jpeg()), 1, false, OffsetDateTime.now());
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
