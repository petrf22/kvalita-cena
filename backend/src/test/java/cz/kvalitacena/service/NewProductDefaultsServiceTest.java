package cz.kvalitacena.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Výběr kategorie podle sousedů v katalogu — remíza ani osamělý soused návrh nedají. */
class NewProductDefaultsServiceTest {

  @Test
  void clearMajorityWins() {
    assertThat(NewProductDefaultsService.pickMajority(counts(1L, 3L, 2L, 1L))).contains(1L);
  }

  @Test
  void tieGivesNothing() {
    assertThat(NewProductDefaultsService.pickMajority(counts(1L, 2L, 2L, 2L))).isEmpty();
  }

  @Test
  void singleNeighbourIsNotAgreement() {
    assertThat(NewProductDefaultsService.pickMajority(counts(1L, 1L, 2L, 1L))).isEmpty();
  }

  @Test
  void emptyGivesNothing() {
    assertThat(NewProductDefaultsService.pickMajority(Map.of())).isEmpty();
  }

  private static Map<Long, Long> counts(long a, long countA, long b, long countB) {
    Map<Long, Long> counts = new LinkedHashMap<>();
    counts.put(a, countA);
    counts.put(b, countB);
    return counts;
  }
}
