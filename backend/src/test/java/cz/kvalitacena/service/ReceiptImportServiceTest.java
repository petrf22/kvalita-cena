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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptImportServiceTest {

  @Mock ReceiptRepository receiptRepository;
  @Mock ReceiptLineRepository receiptLineRepository;
  @Mock StoreRepository storeRepository;
  @Mock RetailChainRepository chainRepository;
  @Mock ReceiptLineMatchingService matchingService;
  @Mock CurrencyResolver currencyResolver;

  private ReceiptImportService service;
  private AppUser uploader;

  @BeforeEach
  void setUp() {
    ReceiptProperties properties = new ReceiptProperties();
    properties.setMaxLinesPerReceipt(300);
    properties.setMaxPerDay(50);
    properties.setTotalTolerance(new BigDecimal("0.02"));
    properties.setRetentionDays(365);
    service = new ReceiptImportService(receiptRepository, receiptLineRepository, storeRepository,
        chainRepository, matchingService, currencyResolver, properties);
    uploader = AppUser.builder().id(3L).build();

    when(currencyResolver.isSupported("CZK")).thenReturn(true);
    when(receiptRepository.findByUploadedByUserIdAndPayloadSha256(any(), any()))
        .thenReturn(Optional.empty());
    when(receiptRepository.countByUploadedByUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
    when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> {
      Receipt receipt = invocation.getArgument(0);
      receipt.setId(42L);
      return receipt;
    });
    when(receiptLineRepository.saveAll(any())).thenAnswer(invocation -> {
      List<ReceiptLine> lines = invocation.getArgument(0);
      long id = 100;
      for (ReceiptLine line : lines) {
        line.setId(id++);
      }
      return lines;
    });
  }

  @Test
  void serverPocitaSoucetZnovuANespolehaSeNaKlienta() {
    // Klient tvrdí, že součet sedí, ale řádky dávají něco jiného — rozhoduje server.
    ReceiptDocument document = document(new BigDecimal("999.00"), true,
        line(1, "ITEM", "ROHLÍK43GR", "PACKAGE", "2.90", "14.50"));

    service.importReceipt(document, uploader);

    verify(receiptRepository).save(argThat(receipt ->
        receipt.getComputedTotal().compareTo(new BigDecimal("14.50")) == 0
            && !receipt.isTotalMatches()));
  }

  @Test
  void soucetVToleranciProjde() {
    ReceiptDocument document = document(new BigDecimal("14.51"), false,
        line(1, "ITEM", "ROHLÍK43GR", "PACKAGE", "2.90", "14.50"));

    ReceiptImportResult result = service.importReceipt(document, uploader);

    assertThat(result.totalMatches()).isTrue();
  }

  @Test
  void slevaSeZapocitavaDoSouctu() {
    ReceiptDocument document = document(new BigDecimal("79.00"), true,
        line(1, "ITEM", "TAPAS", "PACKAGE", "79.00", "158.00"),
        line(2, "DISCOUNT", "Tapas 2+1 zdarma", null, null, "-79.00"));

    ReceiptImportResult result = service.importReceipt(document, uploader);

    assertThat(result.totalMatches()).isTrue();
    assertThat(result.lineCount()).isEqualTo(2);
  }

  @Test
  void opakovaneNahraniVraciTutezUctenku() {
    Receipt existing = Receipt.builder().id(7L).totalMatches(true).build();
    when(receiptRepository.findByUploadedByUserIdAndPayloadSha256(any(), any()))
        .thenReturn(Optional.of(existing));
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(7L)).thenReturn(List.of());

    ReceiptImportResult result = service.importReceipt(
        document(new BigDecimal("14.50"), true, line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")),
        uploader);

    assertThat(result.alreadyImported()).isTrue();
    assertThat(result.receiptId()).isEqualTo(7L);
    verify(receiptRepository, never()).save(any(Receipt.class));
  }

  @Test
  void uctenkaZBudoucnostiSeOdmitne() {
    ReceiptDocument document = new ReceiptDocument(1, "albert", null, null,
        OffsetDateTime.now().plusDays(1), "CZK", new BigDecimal("14.50"), new BigDecimal("14.50"),
        true, List.of(line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")), List.of());

    assertThatThrownBy(() -> service.importReceipt(document, uploader))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_PURCHASED_AT_IN_FUTURE.name());
  }

  @Test
  void neznamaVerzeFormatuSeOdmitne() {
    ReceiptDocument document = new ReceiptDocument(2, "albert", null, null, null, "CZK",
        null, null, null, List.of(line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")), List.of());

    assertThatThrownBy(() -> service.importReceipt(document, uploader))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_SCHEMA_VERSION_UNSUPPORTED.name());
  }

  @Test
  void neznamaMenaSeOdmitne() {
    ReceiptDocument document = new ReceiptDocument(1, "albert", null, null, null, "XYZ",
        null, null, null, List.of(line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")), List.of());

    assertThatThrownBy(() -> service.importReceipt(document, uploader))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_CURRENCY_UNSUPPORTED.name());
  }

  @Test
  void dennyLimitSeUplatni() {
    when(receiptRepository.countByUploadedByUserIdAndCreatedAtAfter(any(), any())).thenReturn(50L);

    assertThatThrownBy(() -> service.importReceipt(
        document(new BigDecimal("14.50"), true, line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")),
        uploader))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_DAILY_LIMIT_REACHED.name());
  }

  @Test
  void provozovnaSeDohledava_aleNikdyNezaklada() {
    RetailChain chain = RetailChain.builder().id(5L).slug("albert").country("CZ").build();
    Store store = Store.builder().id(8L).chain(chain).build();
    when(chainRepository.findFirstBySlugAndCountry("albert", "CZ")).thenReturn(Optional.of(chain));
    when(storeRepository.findByChainCityAndStreet(5L, "CZ", "Rokycany", "B. Němcové 960"))
        .thenReturn(Optional.of(store));

    ReceiptDocument document = new ReceiptDocument(1, "albert", null,
        new ReceiptDocument.Merchant("albert", "albert", "CZ", "B. Němcové 960", "337 01", "Rokycany"),
        OffsetDateTime.now().minusDays(1), "CZK", new BigDecimal("14.50"), new BigDecimal("14.50"),
        true, List.of(line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")), List.of());

    service.importReceipt(document, uploader);

    verify(receiptRepository).save(argThat(receipt ->
        receipt.getStoreId().equals(8L) && receipt.getChainId().equals(5L)));
    verify(storeRepository, never()).save(any());
  }

  @Test
  void neznamaProvozovnaNechaUctenkuBezObchodu() {
    when(chainRepository.findFirstBySlugAndCountry(any(), any())).thenReturn(Optional.empty());

    ReceiptDocument document = new ReceiptDocument(1, "albert", null,
        new ReceiptDocument.Merchant("albert", "albert", "CZ", "Nádražní 1", "100 00", "Vzorov"),
        OffsetDateTime.now().minusDays(1), "CZK", new BigDecimal("14.50"), new BigDecimal("14.50"),
        true, List.of(line(1, "ITEM", "ROHLÍK", "PACKAGE", "2.90", "14.50")), List.of());

    service.importReceipt(document, uploader);

    verify(receiptRepository).save(argThat(receipt ->
        receipt.getStoreId() == null && receipt.getChainId() == null));
  }

  @Test
  void vahoveZboziSiNechaCenuZaKiloNeZaplacenouCastku() {
    ReceiptDocument document = document(new BigDecimal("87.30"), true,
        new ReceiptDocument.Line(1, "ITEM", "LOSOS FILET ASC", null, new BigDecimal("0.302"),
            "PER_KG", new BigDecimal("289.00"), new BigDecimal("87.30"), "A", null, null));

    service.importReceipt(document, uploader);

    verify(receiptLineRepository).saveAll(argThat((List<ReceiptLine> lines) -> {
      ReceiptLine line = lines.getFirst();
      return line.getQuantityBasis() == QuantityBasis.PER_KG
          && line.getUnitPrice().compareTo(new BigDecimal("289.00")) == 0
          && line.getLineTotal().compareTo(new BigDecimal("87.30")) == 0;
    }));
  }

  private ReceiptDocument document(BigDecimal printedTotal, boolean clientClaimsMatch,
      ReceiptDocument.Line... lines) {
    return new ReceiptDocument(1, "albert", null, null, OffsetDateTime.now().minusDays(1), "CZK",
        printedTotal, null, clientClaimsMatch, List.of(lines), List.of());
  }

  private static ReceiptDocument.Line line(int no, String kind, String label, String basis,
      String unitPrice, String lineTotal) {
    return new ReceiptDocument.Line(no, kind, label, null, null, basis,
        unitPrice == null ? null : new BigDecimal(unitPrice),
        lineTotal == null ? null : new BigDecimal(lineTotal), "A", null, null);
  }
}
