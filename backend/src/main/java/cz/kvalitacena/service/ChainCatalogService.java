package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.repo.RetailChainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Číselník řetězců pro našeptávání při zakládání obchodu (docs/stav-implementace.md, "výběr
 * řetězce při zakládání obchodu"). Fixní kurátorský číselník naplněný migrací
 * (2026-08-29/03-retail-chain-seed.yaml) — createChain mutace záměrně neexistuje, stejný vzor
 * jako u kategorií (docs/rozvoj.md, "Číselník je pořád fixní, kurátorský"). Stejný ořez limitu
 * jako {@link StoreSearchService}, aby si klient nemohl vyžádat neomezenou stránku.
 */
@Service
@RequiredArgsConstructor
public class ChainCatalogService {

  private static final int MAX_FIRST = 50;

  private final RetailChainRepository retailChainRepository;
  private final CountryResolver countryResolver;

  @Transactional(readOnly = true)
  public List<RetailChain> search(String query, String country, Integer first, Long viewerUserId) {
    int limitedFirst = clamp(first == null ? 20 : first, 1, MAX_FIRST);
    String normalizedQuery = blankToNull(query);
    String resolvedCountry = countryResolver.resolve(country, viewerUserId);
    return retailChainRepository.searchByText(normalizedQuery, resolvedCountry, limitedFirst);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
