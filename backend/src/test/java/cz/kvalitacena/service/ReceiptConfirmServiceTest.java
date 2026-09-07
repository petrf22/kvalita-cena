package cz.kvalitacena.service;

import cz.kvalitacena.controller.ReceiptConfirmResult;
import cz.kvalitacena.controller.ReceiptLineResult;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import cz.kvalitacena.db.repo.ReceiptRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
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
class ReceiptConfirmServiceTest {

  private static final Long VIEWER = 3L;

  @Mock ReceiptRepository receiptRepository;
  @Mock ReceiptLineRepository receiptLineRepository;
  @Mock StoreRepository storeRepository;
  @Mock ProductRepository productRepository;
  @Mock AppUserRepository appUserRepository;
  @Mock ReceiptLineImporter receiptLineImporter;
  @Mock ReceiptLineMatchingService matchingService;

  private ReceiptConfirmService service;
  private Receipt receipt;
  private Store store;

  @BeforeEach
  void setUp() {
    service = new ReceiptConfirmService(receiptRepository, receiptLineRepository, storeRepository,
        productRepository, appUserRepository, receiptLineImporter, matchingService);
    store = Store.builder().id(8L).build();
    receipt = Receipt.builder().id(1L).uploadedByUserId(VIEWER).storeId(8L).totalMatches(true)
        .purchasedAt(OffsetDateTime.now().minusDays(1)).status(ReceiptStatus.MATCHED).build();

    when(receiptRepository.findById(1L)).thenReturn(Optional.of(receipt));
    when(receiptRepository.save(any(Receipt.class))).thenAnswer(i -> i.getArgument(0));
    when(receiptLineRepository.save(any(ReceiptLine.class))).thenAnswer(i -> i.getArgument(0));
    when(storeRepository.findById(8L)).thenReturn(Optional.of(store));
    when(appUserRepository.findById(VIEWER)).thenReturn(Optional.of(AppUser.builder().id(VIEWER).build()));
  }

  @Test
  void jednaDuplicitaNeshodiZbytekUctenky() {
    ReceiptLine first = matchedLine(1L, 1, "ROHLÍK43GR");
    ReceiptLine second = matchedLine(2L, 2, "CHEDDAR STROUH.150G");
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(1L)).thenReturn(List.of(first, second));
    // Importer výjimku ZÁMĚRNĚ nechytá — musí projít vně hranice REQUIRES_NEW, jinak by
    // odchycení uvnitř transakce shodilo její vlastní commit (UnexpectedRollbackException).
    when(receiptLineImporter.importLine(any(), eq(first), any(), any()))
        .thenThrow(new ValidationException(
            ErrorCode.OBSERVATION_PRICE_KIND_ALREADY_SUBMITTED_TODAY, "REGULAR"));
    when(receiptLineImporter.importLine(any(), eq(second), any(), any()))
        .thenReturn(new ReceiptLineResult(2L, 2, "CHEDDAR STROUH.150G", "WRITTEN", 99L, null));

    ReceiptConfirmResult result = service.confirm(1L, VIEWER);

    assertThat(result.writtenCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isZero();
    assertThat(result.lines()).extracting(ReceiptLineResult::outcome)
        .containsExactly("SKIPPED_DUPLICATE", "WRITTEN");
    assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.CONFIRMED);
  }

  /** Jiná chyba než duplicita se hlásí jako FAILED a taky nezastaví zbytek účtenky. */
  @Test
  void chybaJednohoRadkuNeshodiZbytekUctenky() {
    ReceiptLine first = matchedLine(1L, 1, "ROHLÍK43GR");
    ReceiptLine second = matchedLine(2L, 2, "CHEDDAR STROUH.150G");
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(1L)).thenReturn(List.of(first, second));
    when(receiptLineImporter.importLine(any(), eq(first), any(), any()))
        .thenThrow(new ValidationException(ErrorCode.PRODUCT_NOT_AVAILABLE_AT_STORE));
    when(receiptLineImporter.importLine(any(), eq(second), any(), any()))
        .thenReturn(new ReceiptLineResult(2L, 2, "CHEDDAR STROUH.150G", "WRITTEN", 99L, null));

    ReceiptConfirmResult result = service.confirm(1L, VIEWER);

    assertThat(result.writtenCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(result.lines()).extracting(ReceiptLineResult::errorCode)
        .containsExactly(ErrorCode.PRODUCT_NOT_AVAILABLE_AT_STORE.name(), null);
  }

  @Test
  void navrhSeBezPotvrzeniNezapise() {
    ReceiptLine suggested = matchedLine(1L, 1, "ROHLIK 43 G");
    suggested.setMatchStatus(ReceiptLineMatchStatus.SUGGESTED);
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(1L)).thenReturn(List.of(suggested));

    ReceiptConfirmResult result = service.confirm(1L, VIEWER);

    assertThat(result.lines()).singleElement()
        .extracting(ReceiptLineResult::outcome).isEqualTo("SKIPPED_UNMATCHED");
    verify(receiptLineImporter, never()).importLine(any(), any(), any(), any());
  }

  @Test
  void slevaAniNerozpoznanyRadekNejsouCena() {
    ReceiptLine discount = ReceiptLine.builder().id(1L).lineNo(1).kind(ReceiptLineKind.DISCOUNT)
        .rawLabel("Tapas 2+1 zdarma").matchStatus(ReceiptLineMatchStatus.UNMATCHED).build();
    ReceiptLine unknown = ReceiptLine.builder().id(2L).lineNo(2).kind(ReceiptLineKind.UNKNOWN)
        .rawLabel("ROZMAZANÝ ŘÁDEK").matchStatus(ReceiptLineMatchStatus.UNMATCHED).build();
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(1L)).thenReturn(List.of(discount, unknown));

    ReceiptConfirmResult result = service.confirm(1L, VIEWER);

    assertThat(result.lines()).isEmpty();
    verify(receiptLineImporter, never()).importLine(any(), any(), any(), any());
  }

  @Test
  void uctenkaJejizSoucetNesediSeNepotvrzuje() {
    receipt.setTotalMatches(false);

    assertThatThrownBy(() -> service.confirm(1L, VIEWER))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_TOTAL_MISMATCH.name());
  }

  @Test
  void bezProvozovnySeNepotvrzuje() {
    receipt.setStoreId(null);

    assertThatThrownBy(() -> service.confirm(1L, VIEWER))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_STORE_REQUIRED.name());
  }

  @Test
  void jizPotvrzenaUctenkaSeNepotvrzujeZnovu() {
    receipt.setStatus(ReceiptStatus.CONFIRMED);

    assertThatThrownBy(() -> service.confirm(1L, VIEWER))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ErrorCode.RECEIPT_ALREADY_CONFIRMED.name());
  }

  @Test
  void ciziUctenkaSeTvariJakoNeexistujici() {
    assertThatThrownBy(() -> service.confirm(1L, 99L))
        .isInstanceOf(NotFoundException.class)
        .hasMessage(ErrorCode.RECEIPT_NOT_FOUND.name());
  }

  @Test
  void rucniPrirazeniPrebijeKaskadu() {
    ReceiptLine line = matchedLine(1L, 1, "ROHLÍK43GR");
    line.setMatchStatus(ReceiptLineMatchStatus.SUGGESTED);
    line.setMatchSource(ReceiptMatchSource.SIMILARITY);
    line.setMatchScore(new BigDecimal("0.310"));
    when(receiptLineRepository.findById(1L)).thenReturn(Optional.of(line));
    when(productRepository.existsById(55L)).thenReturn(true);

    ReceiptLine updated = service.matchLine(1L, 55L, VIEWER);

    assertThat(updated.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.MATCHED);
    assertThat(updated.getMatchSource()).isEqualTo(ReceiptMatchSource.MANUAL);
    assertThat(updated.getMatchedProductId()).isEqualTo(55L);
    assertThat(updated.getMatchScore()).isNull();
  }

  @Test
  void zmenaObchoduPustiKaskaduZnovu_aleNechaRucniAPreskocene() {
    ReceiptLine unmatched = matchedLine(1L, 1, "ROHLÍK43GR");
    unmatched.setMatchStatus(ReceiptLineMatchStatus.UNMATCHED);
    ReceiptLine manual = matchedLine(2L, 2, "CHEDDAR");
    ReceiptLine skipped = matchedLine(3L, 3, "PYTLÍK");
    skipped.setMatchStatus(ReceiptLineMatchStatus.SKIPPED);
    when(receiptLineRepository.findByReceiptIdOrderByLineNoAsc(1L))
        .thenReturn(List.of(unmatched, manual, skipped));

    service.setStore(1L, 8L, VIEWER);

    verify(matchingService).match(receipt, unmatched);
    verify(matchingService, never()).match(receipt, manual);
    verify(matchingService, never()).match(receipt, skipped);
  }

  private static ReceiptLine matchedLine(Long id, int no, String label) {
    return ReceiptLine.builder().id(id).receiptId(1L).lineNo(no).kind(ReceiptLineKind.ITEM)
        .rawLabel(label).unitPrice(new BigDecimal("2.90")).quantityBasis(QuantityBasis.PACKAGE)
        .matchStatus(ReceiptLineMatchStatus.MATCHED).matchedProductId(11L)
        .matchSource(ReceiptMatchSource.LABEL).build();
  }
}
