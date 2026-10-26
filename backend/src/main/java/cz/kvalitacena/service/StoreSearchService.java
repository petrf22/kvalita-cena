package cz.kvalitacena.service;

import cz.kvalitacena.controller.StoreSearchResult;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Našeptávač obchodů podle názvu, města a ulice — doplněk k {@code nearbyStores} pro zápis ceny bez
 * sdílení polohy nebo zpětně z domova (docs/datovy-model.md, "Identita provozovny"). Stejný
 * ořez limitů jako {@link ProductSearchService}, aby si klient nemohl vyžádat neomezenou stránku.
 */
@Service
@RequiredArgsConstructor
public class StoreSearchService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;

  /**
   * Oddělovače slov pro výběr nejdelšího slova — NADMNOŽINA toho, čím dělí dotaz SQL
   * ({@code [[:space:],]+} po {@code core.norm_text}): slovo z Javy pak vždy leží uvnitř
   * některého slova z SQL, takže předfiltr nikdy nevyřadí obchod, který by SQL našlo.
   */
  private static final Pattern WORD_SEPARATORS = Pattern.compile("[\\s\\p{Z},]+");

  private final StoreRepository storeRepository;
  private final StoreOverlayService storeOverlayService;

  @Transactional(readOnly = true)
  public StoreSearchResult search(String query, String city, Integer first, Integer offset, Long viewerId) {
    int limitedFirst = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int limitedOffset = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    String normalizedQuery = blankToNull(query);
    String normalizedCity = blankToNull(city);
    String longestWord = longestWord(normalizedQuery);

    List<Store> items = storeOverlayService.applyOverlay(
        storeRepository.searchByText(normalizedQuery, longestWord, normalizedCity, limitedFirst, limitedOffset,
            viewerId),
        viewerId);
    long totalCount = storeRepository.countByText(normalizedQuery, longestWord, normalizedCity, viewerId);
    boolean hasMore = (long) limitedOffset + items.size() < totalCount;
    return new StoreSearchResult(items, (int) totalCount, hasMore);
  }

  /**
   * Nejdelší slovo dotazu jako předfiltr pro trigramový index ({@link StoreRepository#TEXT_MATCH}) —
   * nejvíc trigramů, nejmenší výběr. Dotaz bez slov (jen čárky) dá prázdný řetězec, tedy bez
   * předfiltru, stejně jako ho bere SQL.
   */
  static String longestWord(String query) {
    if (query == null) {
      return null;
    }
    return Arrays.stream(WORD_SEPARATORS.split(query))
        .max(Comparator.comparingInt(String::length))
        .orElse("");
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
