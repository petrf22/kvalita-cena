package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.CatalogDataSource;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductUserEdit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Jediné místo, kde se rozhoduje, JAKÝ název zboží uživatel uvidí (docs/lokalizace.md).
 * Skládá dohromady tři vrstvy — osobní patch, komunitní katalog a snapshot OFF — a k nim
 * jazyk requestu.
 *
 * <p><b>Uvnitř jednoho jazyka</b> platí pořadí patch → komunita → OFF. To je vědomá změna
 * oproti stavu do 2026-09, kdy název z OFF přebíjel {@code core.product.name} vždy: komunitní
 * překlad vzniká právě proto, že v OFF v daném jazyce nic nebylo, a později doplněný
 * {@code product_name_cs} v OFF by ho jinak tiše přebil.
 *
 * <p><b>Napříč jazyky</b> se sahá až tehdy, když v jazyce klienta název nemá nikdo — protože
 * název ve špatném jazyce je pořád lepší než prázdno. Pořadí fallbacku: primární název zboží →
 * hlavní jazyk OFF → výchozí jazyk appky → cokoli dalšího (abecedně, ať je výsledek
 * deterministický) → „hlavní" název z OFF, u kterého jazyk neznáme.
 */
@Service
@RequiredArgsConstructor
public class ProductNameResolver {

  private final I18nProperties i18nProperties;

  /** Jazyk requestu ({@code Accept-Language} / uložená preference) — týž zdroj jako u kategorií. */
  public String requestLanguage() {
    return LocaleContextHolder.getLocale().getLanguage();
  }

  /**
   * Všechny známé názvy zboží po jazycích, seřazené tak, že jazyk requestu je první.
   * Slouží editaci (které jazyky už zboží má) i detailu; {@link #effective} z toho pak vybírá
   * jeden pro zobrazení.
   */
  public List<ResolvedProductName> allNames(Product stored, OffProduct off, ProductUserEdit edit,
      Collection<ProductName> communityNames) {
    Map<String, ResolvedProductName> byLang = new TreeMap<>();

    if (off != null) {
      off.getNames().forEach((lang, name) -> byLang.put(lang,
          new ResolvedProductName(lang, name, CatalogDataSource.OPEN_FOOD_FACTS, false)));
    }
    if (stored != null && stored.getName() != null) {
      byLang.put(stored.getNameLang(), new ResolvedProductName(
          stored.getNameLang(), stored.getName(), CatalogDataSource.COMMUNITY, false));
    }
    for (ProductName name : communityNames) {
      byLang.put(name.getLang(),
          new ResolvedProductName(name.getLang(), name.getName(), CatalogDataSource.COMMUNITY, false));
    }
    if (edit != null && edit.getName() != null && edit.getNameLang() != null) {
      byLang.put(edit.getNameLang(),
          new ResolvedProductName(edit.getNameLang(), edit.getName(), CatalogDataSource.COMMUNITY, true));
    }

    String requested = requestLanguage();
    List<ResolvedProductName> ordered = new ArrayList<>();
    ResolvedProductName first = byLang.remove(requested);
    if (first != null) ordered.add(first);
    ordered.addAll(byLang.values());
    return List.copyOf(ordered);
  }

  /**
   * Název pro zobrazení v jazyce requestu, nebo nejbližší náhrada. {@code null} jen u zboží,
   * které nemá název nikde — což je v katalogu stav neplatný, ale volající ho nesmí ignorovat
   * (bezkódová položka rozpracovaná jinou cestou).
   */
  public ResolvedProductName effective(Product stored, OffProduct off, ProductUserEdit edit,
      Collection<ProductName> communityNames) {
    List<ResolvedProductName> names = allNames(stored, off, edit, communityNames);
    if (names.isEmpty()) return offFallback(off);

    String requested = requestLanguage();
    ResolvedProductName inRequested = names.getFirst();
    if (requested.equals(inRequested.lang())) return inRequested;

    for (String lang : fallbackOrder(stored, off)) {
      for (ResolvedProductName candidate : names) {
        if (candidate.lang().equals(lang)) return candidate;
      }
    }
    return names.getFirst();
  }

  /** Po jazyku requestu (řešeném výš) tohle pořadí, pak zbytek v abecedním pořadí z allNames. */
  private List<String> fallbackOrder(Product stored, OffProduct off) {
    List<String> order = new ArrayList<>();
    if (stored != null && stored.getName() != null) order.add(stored.getNameLang());
    if (off != null && off.getLang() != null) order.add(off.getLang());
    if (i18nProperties.getDefaultLocale() != null) {
      order.add(Locale.forLanguageTag(i18nProperties.getDefaultLocale()).getLanguage());
    }
    return order;
  }

  /** Poslední záchrana: „hlavní" název z OFF, u kterého se jazyk poznat nedá. */
  private ResolvedProductName offFallback(OffProduct off) {
    if (off == null || off.getProductName() == null) return null;
    return new ResolvedProductName(off.getLang(), off.getProductName(), CatalogDataSource.OPEN_FOOD_FACTS, false);
  }

  /**
   * Názvy jen z OFF snapshotu — pro nabídku nového zboží, kde ještě žádná vlastní vrstva
   * neexistuje. Zachovává totéž řazení (jazyk requestu první) jako {@link #allNames}.
   */
  public List<ResolvedProductName> offNames(OffProduct off) {
    if (off == null) return List.of();
    Map<String, String> names = new TreeMap<>(off.getNames());
    Map<String, String> ordered = new LinkedHashMap<>();
    String requested = requestLanguage();
    if (names.containsKey(requested)) ordered.put(requested, names.remove(requested));
    ordered.putAll(names);
    return ordered.entrySet().stream()
        .map(e -> new ResolvedProductName(e.getKey(), e.getValue(), CatalogDataSource.OPEN_FOOD_FACTS, false))
        .toList();
  }

  /** Nabízený název pro formulář nového zboží — jazyk klienta, jinak fallback jako u {@link #effective}. */
  public ResolvedProductName effectiveOffName(OffProduct off) {
    if (off == null) return null;
    List<ResolvedProductName> names = offNames(off);
    if (names.isEmpty()) return offFallback(off);
    String requested = requestLanguage();
    if (requested.equals(names.getFirst().lang())) return names.getFirst();
    for (String lang : fallbackOrder(null, off)) {
      for (ResolvedProductName candidate : names) {
        if (candidate.lang().equals(lang)) return candidate;
      }
    }
    return names.getFirst();
  }
}
