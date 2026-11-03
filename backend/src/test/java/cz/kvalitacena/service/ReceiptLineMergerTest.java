package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.LabelDisplaySource;
import cz.kvalitacena.db.entity.ReceiptLine;
import cz.kvalitacena.db.entity.ReceiptLineStatus;
import cz.kvalitacena.db.entity.ReceiptMatchSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Sloučení řádků při novém rozpoznání účtenky (docs/ai.md, „Nové rozpoznání"). */
class ReceiptLineMergerTest {

  @Test
  void untouchedLinesAreReplacedCompletely() {
    ReceiptLine old = line(0, "ROHLIK", "2.90");
    var result = ReceiptLineMerger.merge(List.of(old), List.of(fresh("ROHLÍK 43G", "2.90"), fresh("MLEKO", "21.90")));

    assertThat(result.removed()).containsExactly(old);
    assertThat(result.added()).containsExactly(0, 1);
    assertThat(result.ordered()).extracting(ReceiptLine::getLabelRaw).containsExactly("ROHLÍK 43G", "MLEKO");
  }

  @Test
  void confirmedLineStaysAndItsNewCopyIsDropped() {
    ReceiptLine confirmed = confirmed(1, "MLEKO 1L", "21.90");
    ReceiptLine untouched = line(0, "ROHLIK", "2.90");
    ReceiptLine copy = fresh("mleko  1l", "21.90");
    copy.setLabelDisplay("Mléko 1l");
    copy.setLabelDisplaySource(LabelDisplaySource.DICT);

    var result = ReceiptLineMerger.merge(List.of(untouched, confirmed), List.of(fresh("ROHLIK", "2.90"), copy));

    assertThat(result.removed()).containsExactly(untouched);
    assertThat(result.added()).containsExactly(0);
    assertThat(result.ordered()).element(1).isSameAs(confirmed);
    // Kosmetika přejde, syrový tisk a zboží ne.
    assertThat(confirmed.getLabelDisplay()).isEqualTo("Mléko 1l");
    assertThat(confirmed.getLabelRaw()).isEqualTo("MLEKO 1L");
    assertThat(confirmed.getProductId()).isEqualTo(7L);
  }

  @Test
  void correctedOcrLabelPairsByAmounts() {
    ReceiptLine ignored = line(0, "NUGETA ARASIDOVA34OG", "59.90");
    ignored.setStatus(ReceiptLineStatus.IGNORED);

    var result = ReceiptLineMerger.merge(List.of(ignored), List.of(fresh("NUGETA ARASÍDOVÁ340G", "59.90")));

    assertThat(result.added()).isEmpty();
    assertThat(result.ordered()).containsExactly(ignored);
  }

  @Test
  void ambiguousAmountsDoNotPairAndKeptLineGoesLast() {
    ReceiptLine kept = line(0, "PIVO", "19.90");
    kept.setMatchSource(ReceiptMatchSource.USER);

    var result = ReceiptLineMerger.merge(List.of(kept), List.of(fresh("RADLER", "19.90"), fresh("LIMONADA", "19.90")));

    assertThat(result.added()).containsExactly(0, 1);
    assertThat(result.ordered()).extracting(ReceiptLine::getLabelRaw).containsExactly("RADLER", "LIMONADA", "PIVO");
  }

  @Test
  void twoKeptLinesCompetingForOneNewLineStayUnpaired() {
    ReceiptLine a = line(0, "PIVO A", "19.90");
    a.setStatus(ReceiptLineStatus.IGNORED);
    ReceiptLine b = line(1, "PIVO B", "19.90");
    b.setStatus(ReceiptLineStatus.IGNORED);

    var result = ReceiptLineMerger.merge(List.of(a, b), List.of(fresh("PIVO X", "19.90")));

    assertThat(result.added()).containsExactly(0);
    assertThat(result.ordered()).extracting(ReceiptLine::getLabelRaw).containsExactly("PIVO X", "PIVO A", "PIVO B");
  }

  @Test
  void sameContentDetectsNoChange() {
    ReceiptLine old = line(0, "ROHLIK", "2.90");
    assertThat(ReceiptLineMerger.sameContent(List.of(old), List.of(fresh("ROHLIK", "2.900")))).isTrue();
    assertThat(ReceiptLineMerger.sameContent(List.of(old), List.of(fresh("ROHLIK", "3.90")))).isFalse();
    assertThat(ReceiptLineMerger.sameContent(List.of(old), List.of())).isFalse();
  }

  private static ReceiptLine confirmed(int lineNo, String label, String total) {
    ReceiptLine line = line(lineNo, label, total);
    line.setStatus(ReceiptLineStatus.MATCHED);
    line.setMatchSource(ReceiptMatchSource.LABEL);
    line.setProductId(7L);
    line.setObservationId(99L);
    return line;
  }

  private static ReceiptLine line(int lineNo, String label, String total) {
    ReceiptLine line = fresh(label, total);
    line.setLineNo((short) lineNo);
    return line;
  }

  private static ReceiptLine fresh(String label, String total) {
    return ReceiptLine.builder().labelRaw(label).quantity(BigDecimal.ONE)
        .unitPrice(new BigDecimal(total)).lineTotal(new BigDecimal(total)).build();
  }
}
