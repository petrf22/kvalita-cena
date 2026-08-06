package cz.kvalitacena.service;

import cz.kvalitacena.controller.StoreSearchResult;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Našeptávač obchodů podle názvu/města — doplněk k {@code nearbyStores} pro zápis ceny bez
 * sdílení polohy nebo zpětně z domova (docs/datovy-model.md, "Identita provozovny"). Stejný
 * ořez limitů jako {@link ProductSearchService}, aby si klient nemohl vyžádat neomezenou stránku.
 */
@Service
@RequiredArgsConstructor
public class StoreSearchService {

  private static final int MAX_FIRST = 50;
  private static final int MAX_OFFSET = 500;

  private final StoreRepository storeRepository;
  private final StoreOverlayService storeOverlayService;

  @Transactional(readOnly = true)
  public StoreSearchResult search(String query, String city, Integer first, Integer offset, Long viewerId) {
    int limitedFirst = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    int limitedOffset = clamp(offset == null ? 0 : offset, 0, MAX_OFFSET);
    String normalizedQuery = blankToNull(query);
    String normalizedCity = blankToNull(city);

    List<Store> items = storeOverlayService.applyOverlay(
        storeRepository.searchByText(normalizedQuery, normalizedCity, limitedFirst, limitedOffset, viewerId),
        viewerId);
    long totalCount = storeRepository.countByText(normalizedQuery, normalizedCity, viewerId);
    boolean hasMore = (long) limitedOffset + items.size() < totalCount;
    return new StoreSearchResult(items, (int) totalCount, hasMore);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
