package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.ProductCodeRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kaskáda párování z docs/rozvoj.md — první, co sedne, vyhrává; podobnost je jen návrh. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptLineMatchingServiceTest {

  @Mock ProductCodeRepository productCodeRepository;
  @Mock ProductStoreLabelRepository labelRepository;
  @Mock ProductRepository productRepository;

  private ReceiptLineMatchingService service;
  private Receipt receipt;

  @BeforeEach
  void setUp() {
    CatalogProperties properties = new CatalogProperties();
    properties.setSuggestionSimilarity(0.2);
    service = new ReceiptLineMatchingService(productCodeRepository, labelRepository,
        productRepository, properties);
    receipt = Receipt.builder().id(1L).chainId(5L).storeId(8L).build();

    when(productCodeRepository.findFirstByCodeAndCodeTypeAndChainId(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(labelRepository.findActiveByLabel(any(), any(), any())).thenReturn(Optional.empty());
    when(labelRepository.findSimilar(any(), any(), any(), anyDouble())).thenReturn(List.of());
    when(productRepository.findSimilarByName(any(), any(), any(), anyDouble(), anyInt()))
        .thenReturn(List.of());
  }

  @Test
  void vnitroobchodniKodJeJistota_aVzdySeScopemRetezce() {
    ReceiptLine line = line("SYR VAZENY", "2801234");
    when(productCodeRepository.findFirstByCodeAndCodeTypeAndChainId("2801234",
        CodeType.STORE_INTERNAL, 5L))
        .thenReturn(Optional.of(ProductCode.builder()
            .product(Product.builder().id(11L).build()).build()));

    service.match(receipt, line);

    assertThat(line.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.MATCHED);
    assertThat(line.getMatchSource()).isEqualTo(ReceiptMatchSource.CODE);
    assertThat(line.getMatchedProductId()).isEqualTo(11L);
    verify(labelRepository, never()).findActiveByLabel(any(), any(), any());
  }

  @Test
  void presneOznaceniJeTakyJistota() {
    ReceiptLine line = line("ROHLÍK43GR", null);
    when(labelRepository.findActiveByLabel("ROHLÍK43GR", 8L, 5L))
        .thenReturn(Optional.of(ProductStoreLabel.builder().id(2L).productId(22L).build()));

    service.match(receipt, line);

    assertThat(line.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.MATCHED);
    assertThat(line.getMatchSource()).isEqualTo(ReceiptMatchSource.LABEL);
    assertThat(line.getMatchedProductId()).isEqualTo(22L);
  }

  @Test
  void podobnostJeJenNavrh_nikdyPotvrzenaShoda() {
    ReceiptLine line = line("ROHLIK 43 G", null);
    when(labelRepository.findSimilar(eq("ROHLIK 43 G"), eq(8L), eq(5L), anyDouble()))
        .thenReturn(List.<Object[]>of(new Object[] {33L, new BigDecimal("0.640")}));

    service.match(receipt, line);

    assertThat(line.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.SUGGESTED);
    assertThat(line.getMatchSource()).isEqualTo(ReceiptMatchSource.SIMILARITY);
    assertThat(line.getMatchedProductId()).isEqualTo(33L);
    assertThat(line.getMatchScore()).isEqualByComparingTo("0.640");
  }

  @Test
  void kdyzOznaceniNesedi_zkusiSeNazevZbozi() {
    ReceiptLine line = line("CHEDDAR STROUH.150G", null);
    when(productRepository.findSimilarByName(eq("CHEDDAR STROUH.150G"), eq(8L), isNull(),
        anyDouble(), eq(1)))
        .thenReturn(List.of(Product.builder().id(44L).build()));

    service.match(receipt, line);

    assertThat(line.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.SUGGESTED);
    assertThat(line.getMatchedProductId()).isEqualTo(44L);
  }

  @Test
  void nicNesedi_radekZustaneNesparovanyABezZbozi() {
    ReceiptLine line = line("UPLNE NECO JINEHO", null);

    service.match(receipt, line);

    assertThat(line.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.UNMATCHED);
    assertThat(line.getMatchedProductId()).isNull();
    assertThat(line.getMatchSource()).isNull();
  }

  @Test
  void slevaSeNeparuje() {
    ReceiptLine item = line("ROHLÍK43GR", null);
    ReceiptLine discount = ReceiptLine.builder().kind(ReceiptLineKind.DISCOUNT)
        .rawLabel("Tapas 2+1 zdarma").matchStatus(ReceiptLineMatchStatus.UNMATCHED).build();
    when(labelRepository.findActiveByLabel(eq("ROHLÍK43GR"), any(), any()))
        .thenReturn(Optional.of(ProductStoreLabel.builder().id(2L).productId(22L).build()));

    service.matchAll(receipt, List.of(item, discount));

    assertThat(item.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.MATCHED);
    assertThat(discount.getMatchStatus()).isEqualTo(ReceiptLineMatchStatus.UNMATCHED);
    assertThat(discount.getMatchedProductId()).isNull();
  }

  private static ReceiptLine line(String label, String code) {
    return ReceiptLine.builder().kind(ReceiptLineKind.ITEM).rawLabel(label).rawCode(code)
        .matchStatus(ReceiptLineMatchStatus.UNMATCHED).build();
  }
}
