package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.ProductNameInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.OffProduct;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductName;
import cz.kvalitacena.db.entity.ProductUserEdit;
import cz.kvalitacena.db.repo.ProductNameRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Zápis názvů zboží po jazycích — jediné místo, kde se rozhoduje, jestli název skončí
 * GLOBÁLNĚ, nebo jen v osobním patchi (docs/datovy-model.md, „Uživatelská vrstva nad
 * globálními daty").
 *
 * <p>Pravidlo: <b>doplnění jazyka, ve kterém zboží zatím žádný název nemá, je globální</b>
 * ({@code core.product.name} pro primární jazyk, jinak {@code core.product_name}), zatímco
 * <b>změna už existujícího názvu zůstává osobní</b> ({@code core.product_user_edit}). Bez
 * toho prvního by český překlad německého názvu z OFF viděl navždycky jen ten, kdo ho napsal,
 * a každý další česky mluvící uživatel by narazil na totéž (přesně to byl důvod celé změny).
 *
 * <p>Doplnění NEVYŽADUJE práh důvěry ({@link TrustLevelService}) — a je to vědomé. Zboží
 * samotné smí založit každý přihlášený (nedůvěryhodný autor dostane DRAFT, ne odmítnutí)
 * a chybějící jazyk je tatáž třída příspěvku: přidaná informace, ne nesouhlas s existující.
 * Práh důvěry by ho navíc zavřel právě těm, kdo na cizojazyčné zboží narážejí nejčastěji —
 * novým uživatelům. Obrana proti nesmyslům je stejná jako u zbytku katalogu: nahlášení
 * a moderace (docs/reputace.md).
 *
 * <p>Osobní patch nese JEDEN jazyk ({@code product_user_edit.name_lang}), takže přepsat
 * existující názvy ve dvou jazycích najednou nejde — doplnit chybějící libovolně mnoho ano.
 */
@Service
@RequiredArgsConstructor
public class ProductNameWriter {

  private final ProductNameRepository productNameRepository;
  private final I18nProperties i18nProperties;

  /**
   * Zpracuje primární název i další jazyky. Globální řádky zapisuje rovnou; do {@code edit}
   * jen NASTAVÍ pole (uložení/smazání prázdného patche zůstává na volajícím, který zná
   * i zbytek patche).
   *
   * @param storedProduct spravovaná entita PŘED překryvem — kvůli primárnímu jazyku a názvu
   */
  public void apply(Product storedProduct, OffProduct off, ProductUserEdit edit,
      String primaryName, String primaryLang, List<ProductNameInput> otherNames, AppUser user) {
    Map<String, String> requested = normalize(primaryName, primaryLang, otherNames);
    if (requested.isEmpty()) return;

    List<ProductName> existing = productNameRepository.findByProductId(storedProduct.getId());
    Map<String, ProductName> communityByLang = new LinkedHashMap<>();
    existing.forEach(name -> communityByLang.put(name.getLang(), name));

    List<String> patched = new ArrayList<>();
    for (Map.Entry<String, String> entry : requested.entrySet()) {
      String lang = entry.getKey();
      String name = entry.getValue();
      String globalValue = globalValue(storedProduct, off, communityByLang, lang);

      if (globalValue == null) {
        writeGlobal(storedProduct, communityByLang, lang, name, user);
        clearPatchFor(edit, lang);
      } else if (globalValue.equals(name)) {
        // Uživatel potvrdil globální hodnotu — patch na ni už nemusí ukazovat sám na sebe.
        clearPatchFor(edit, lang);
      } else {
        patched.add(lang);
        edit.setName(name);
        edit.setNameLang(lang);
      }
    }
    if (patched.size() > 1) throw new ValidationException(ErrorCode.PRODUCT_NAME_EDIT_SINGLE_LANG);
  }

  /**
   * Názvy pro právě zakládané zboží — všechny jdou globálně, žádný patch tu ještě není.
   * Primární název už na entitě sedí ({@code core.product.name}), takže se jen ověří, že ho
   * klient neposlal podruhé v {@code otherNames}, a zapíšou se zbylé jazyky.
   */
  public void applyOnCreate(Product storedProduct, String primaryName, String primaryLang,
      List<ProductNameInput> otherNames, AppUser user) {
    Map<String, String> requested = normalize(primaryName, primaryLang, otherNames);
    Map<String, ProductName> communityByLang = new LinkedHashMap<>();
    requested.forEach((lang, name) -> {
      if (!lang.equals(storedProduct.getNameLang())) {
        writeGlobal(storedProduct, communityByLang, lang, name, user);
      }
    });
  }

  /**
   * Jazyk primárního názvu: co poslal klient, jinak jazyk requestu, jinak výchozí jazyk
   * appky. Nikdy se nehádá z textu — jazyk zná klient, ne server (docs/lokalizace.md).
   */
  public String primaryLang(String requestedLang, String requestLanguage) {
    String candidate = requestedLang != null && !requestedLang.isBlank() ? requestedLang : requestLanguage;
    String normalized = language(candidate);
    if (normalized == null || !isSupported(normalized)) {
      normalized = language(i18nProperties.getDefaultLocale());
    }
    return normalized;
  }

  /** Hodnota, kterou v daném jazyce vidí VŠICHNI (bez osobních patchů); {@code null} = díra. */
  private String globalValue(Product product, OffProduct off, Map<String, ProductName> community, String lang) {
    if (lang.equals(product.getNameLang()) && product.getName() != null) return product.getName();
    ProductName communityName = community.get(lang);
    if (communityName != null) return communityName.getName();
    return off == null ? null : off.getNames().get(lang);
  }

  private void writeGlobal(Product product, Map<String, ProductName> community, String lang,
      String name, AppUser user) {
    if (lang.equals(product.getNameLang()) && product.getName() == null) {
      // Zboží založené nad OFF snapshotem má core.product.name schválně NULL (OFF hodnoty se
      // do core.* nekopírují) — první vlastní název v primárním jazyce patří sem, ne do
      // core.product_name, jinak by byl název ve dvou tabulkách naráz.
      product.setName(name);
      return;
    }
    ProductName row = community.get(lang);
    if (row == null) {
      row = ProductName.builder().productId(product.getId()).lang(lang).name(name)
          .createdByUserId(user == null ? null : user.getId()).build();
      community.put(lang, row);
    } else {
      row.setName(name);
    }
    productNameRepository.save(row);
  }

  private void clearPatchFor(ProductUserEdit edit, String lang) {
    if (edit != null && lang.equals(edit.getNameLang())) {
      edit.setName(null);
      edit.setNameLang(null);
    }
  }

  /**
   * Ořez, kontrola jazyků a sloučení primárního názvu s ostatními do jedné mapy. Duplicitní
   * jazyk je chyba, ne „poslední vyhrává" — klient by jinak tiše zahodil, co uživatel napsal.
   */
  private Map<String, String> normalize(String primaryName, String primaryLang,
      List<ProductNameInput> otherNames) {
    Map<String, String> result = new LinkedHashMap<>();
    if (primaryName != null) {
      result.put(requireSupported(primaryLang), requireNonBlank(primaryName));
    }
    if (otherNames == null) return result;
    for (ProductNameInput input : otherNames) {
      String lang = requireSupported(input.lang());
      if (result.put(lang, requireNonBlank(input.name())) != null) {
        throw new ValidationException(ErrorCode.PRODUCT_NAME_LANG_DUPLICATE);
      }
    }
    return result;
  }

  private String requireNonBlank(String value) {
    if (value == null || value.isBlank()) throw new ValidationException(ErrorCode.PRODUCT_NAME_EMPTY);
    return value.trim();
  }

  private String requireSupported(String value) {
    String lang = language(value);
    if (lang == null || !isSupported(lang)) {
      throw new ValidationException(ErrorCode.PRODUCT_NAME_LANG_UNSUPPORTED);
    }
    return lang;
  }

  private boolean isSupported(String lang) {
    return supportedLanguages().contains(lang);
  }

  private List<String> supportedLanguages() {
    return Optional.ofNullable(i18nProperties.getSupportedLocales()).orElse(List.of())
        .stream().map(this::language).filter(java.util.Objects::nonNull).toList();
  }

  private String language(String value) {
    if (value == null || value.isBlank()) return null;
    String lang = Locale.forLanguageTag(value.trim()).getLanguage();
    return lang.isEmpty() ? null : lang.toLowerCase(Locale.ROOT);
  }
}
