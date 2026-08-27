package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.service.GtinNormalization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code searchProducts} umí od "Hledat ceny tohoto zboží" (mobil, PriceEntryScreen) najít
 * zboží i podle čárového kódu, ne jen podle názvu — viz {@link ProductSearchRepositoryImpl}
 * (větev {@code candidate} CTE) a {@code ProductSearchService.codeQuery}. Stejná třída pokrývá
 * i hledání podle kategorie (celý podstrom, lokalizovaný název i slug, sjednocená diakritika
 * přes core.norm_text) a explicitní filtr {@code categoryId} — nativní SQL, které Mockito
 * neověří, proto Testcontainers jako {@link PriceObservationRepositoryIntegrationTest}.
 *
 * <p>Klíčové regrese, které tenhle test hlídá: hledání podle kódu smí najít jen
 * {@code code_type = GTIN}, NIKDY {@code STORE_INTERNAL} (vnitroobchodní, platí jen v rámci
 * jednoho řetězce); shoda na kategorii bere podstrom PŘES hranici lomítka v {@code path}, ne
 * jako holý textový prefix; slovní AND fulltextu ("bio mleko" najde "Mléko bio", nenajde
 * samotné "Mléko") přežil přechod na {@code core.norm_text}; a viditelnost (hidden/DRAFT
 * cizího uživatele) platí i pro kandidáty nalezené přes kategorii, ne jen přes název.
 */
@Testcontainers
@SpringBootTest
class ProductSearchRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired
  private ProductRepository productRepository;
  @Autowired
  private ProductCodeRepository productCodeRepository;
  @Autowired
  private OffProductRepository offProductRepository;
  @Autowired
  private CategoryRepository categoryRepository;
  @Autowired
  private CategoryI18nRepository categoryI18nRepository;
  @Autowired
  private RetailChainRepository retailChainRepository;
  @Autowired
  private AppUserRepository appUserRepository;

  /** core.product.created_by_user_id má FK na auth.app_user — DRAFT test potřebuje reálný řádek. */
  private AppUser persistUser() {
    String unique = UUID.randomUUID().toString();
    return appUserRepository.saveAndFlush(AppUser.builder()
        .emailHash(unique.getBytes())
        .emailEnc(unique.getBytes())
        .publicHandle(unique.substring(0, 30)) // auth.app_user.public_handle je VARCHAR(40)
        .handleAdjective("blue")
        .handleNoun("stork")
        .handleNumber((short) 1)
        .status(AppUserStatus.ACTIVE)
        .build());
  }

  /** Kategorie mimo jakýkoli testovaný strom — jméno/slug záměrně nesouvisí s hledanými slovy. */
  private Category persistCategory() {
    String suffix = UUID.randomUUID().toString();
    return persistCategory("Test kategorie " + suffix, "test-" + suffix, null);
  }

  /**
   * Kategorie s vlastní cestou/rodičem pro testy podstromu a lokalizace — na rozdíl od reálného
   * seedu (Testcontainers načte i {@code 2026-08-20/01-category-tree.yaml}) musí {@code slug}
   * zůstat volajícím kódem zaručeně unikátní (typicky přípona UUID), NIKDY napevno "mleko"
   * apod., jinak spadne na {@code core.category.slug} unique constraint. {@code path} se skládá
   * materializovaně z rodičovské cesty, stejně jako reálný seed.
   */
  private Category persistCategory(String name, String slug, Category parent) {
    String path = parent == null ? slug : parent.getPath() + "/" + slug;
    return categoryRepository.saveAndFlush(
        Category.builder().name(name).slug(slug).path(path).parent(parent).build());
  }

  private Product persistProduct(Category category, String name) {
    return productRepository.saveAndFlush(Product.builder()
        .name(name)
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
        .status(ProductStatus.ACTIVE)
        .build());
  }

  /** Náhodných 13 číslic — testu stačí unikátní numerický řetězec, ne platný EAN-13 kontrolní součet. */
  private String randomCode() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 13; i++) sb.append(ThreadLocalRandom.current().nextInt(10));
    return sb.toString();
  }

  private ProductSearchCriteria criteriaByCode(String codeQuery) {
    return new ProductSearchCriteria(
        "", codeQuery, null, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, null, "cs");
  }

  private ProductSearchCriteria criteria(String query, Long categoryId, String locale, int first) {
    return new ProductSearchCriteria(
        query, null, null, null, categoryId, "CZ", ProductSort.REPORT_COUNT, first, 0, null, locale);
  }

  private ProductSearchCriteria criteria(String query, Long categoryId, String locale) {
    return criteria(query, categoryId, locale, 20);
  }

  private ProductSearchCriteria criteria(String query, Long categoryId) {
    return criteria(query, categoryId, "cs");
  }

  private ProductSearchCriteria criteria(String query) {
    return criteria(query, null);
  }

  @Test
  void searchByCodeFindsProductByGtin() {
    Category category = persistCategory();
    Product product = persistProduct(category, "Search Test GTIN " + UUID.randomUUID());
    String rawCode = randomCode();
    String gtin14 = GtinNormalization.toGtin14(rawCode);
    productCodeRepository.saveAndFlush(ProductCode.builder()
        .product(product).code(gtin14).codeType(CodeType.GTIN).build());

    List<ProductSearchRow> rows = productRepository.search(criteriaByCode(gtin14));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void searchByCodeIgnoresStoreInternalCode() {
    Category category = persistCategory();
    Product product = persistProduct(category, "Search Test STORE_INTERNAL " + UUID.randomUUID());
    RetailChain chain = retailChainRepository.saveAndFlush(RetailChain.builder()
        .name("Test řetězec " + UUID.randomUUID())
        .slug("test-chain-" + UUID.randomUUID())
        .chainType(ChainType.CHAIN)
        .country("CZ")
        .build());
    String rawCode = randomCode();
    String gtin14 = GtinNormalization.toGtin14(rawCode);
    // Stejná hodnota kódu jako u GTIN v testu výš, ale STORE_INTERNAL — nesmí se najít.
    productCodeRepository.saveAndFlush(ProductCode.builder()
        .product(product).code(gtin14).codeType(CodeType.STORE_INTERNAL).chain(chain).build());

    List<ProductSearchRow> rows = productRepository.search(criteriaByCode(gtin14));

    assertThat(rows).extracting(ProductSearchRow::productId).doesNotContain(product.getId());
  }

  @Test
  void searchWithoutCodeQueryStillMatchesByName() {
    Category category = persistCategory();
    String uniqueName = "Search Test Fulltext " + UUID.randomUUID();
    Product product = persistProduct(category, uniqueName);

    List<ProductSearchRow> rows = productRepository.search(criteria(uniqueName));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void searchFindsOffNameAndUsesMappedCategoryForFilter() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Category mapped = persistCategory("OFF mapped " + suffix, "off-mapped-" + suffix, null);
    Category other = persistCategory();
    Product product = productRepository.saveAndFlush(Product.builder().status(ProductStatus.ACTIVE).build());
    String gtin = GtinNormalization.toGtin14(randomCode());
    productCodeRepository.saveAndFlush(ProductCode.builder().product(product).code(gtin)
        .codeType(CodeType.GTIN).primary(true).build());
    offProductRepository.saveAndFlush(OffProduct.builder().gtin(gtin).fetchStatus(OffFetchStatus.FOUND)
        .productName("offsearch" + suffix).mappedCategorySlug(mapped.getSlug())
        .fetchedAt(OffsetDateTime.now()).build());

    List<ProductSearchRow> found = productRepository.search(criteria("offsearch" + suffix, mapped.getId()));
    List<ProductSearchRow> filteredOut = productRepository.search(criteria("offsearch" + suffix, other.getId()));

    assertThat(found).extracting(ProductSearchRow::productId).contains(product.getId());
    assertThat(filteredOut).extracting(ProductSearchRow::productId).doesNotContain(product.getId());
  }

  @Test
  void matchesProductViaCategoryName() {
    String suffix = UUID.randomUUID().toString();
    Category mleko = persistCategory("Mléko", "mleko-" + suffix, null);
    // Název produktu záměrně NEobsahuje "mléko" — shoda smí přijít jen přes kategorii.
    Product product = persistProduct(mleko, "bio 3,5% tuku " + suffix);

    List<ProductSearchRow> rows = productRepository.search(criteria("mléko"));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void matchesProductViaCategoryNameWithoutDiacritics() {
    String suffix = UUID.randomUUID().toString();
    Category mleko = persistCategory("Mléko", "mleko-" + suffix, null);
    Product product = persistProduct(mleko, "bio 3,5% tuku " + suffix);

    List<ProductSearchRow> rows = productRepository.search(criteria("mleko"));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void matchesProductNameWithoutDiacritics() {
    Category category = persistCategory();
    Product product = persistProduct(category, "Mléko polotučné " + UUID.randomUUID());

    List<ProductSearchRow> rows = productRepository.search(criteria("mleko"));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void categoryMatchIncludesSubtree() {
    String suffix = UUID.randomUUID().toString();
    Category dairy = persistCategory("Mléčné výrobky", "mlecne-" + suffix, null);
    Category butter = persistCategory("Máslo", "maslo-" + suffix, dairy);
    Category milk = persistCategory("Mléko", "mleko-" + suffix, dairy);
    // Jména produktů záměrně bez "mléčné" — shoda musí přijít z podstromu kategorie "dairy".
    Product butterProduct = persistProduct(butter, "Butter subtree test " + suffix);
    Product milkProduct = persistProduct(milk, "Milk subtree test " + suffix);

    List<ProductSearchRow> rows = productRepository.search(criteria("mléčné"));

    assertThat(rows).extracting(ProductSearchRow::productId)
        .contains(butterProduct.getId(), milkProduct.getId());
  }

  @Test
  void categoryMatchDoesNotLeakToSiblingPrefix() {
    String suffix = UUID.randomUUID().toString();
    Category root = persistCategory("Test root", "root-" + suffix, null);
    Category dairy = persistCategory("Mléčné výrobky", "branch-" + suffix, root);
    // Slug NAVAZUJE textově na dairy.path bez lomítka ("…/branch-<suffix>" + "-x") — přesně
    // past, kterou má "path = X OR path LIKE X || '/%'" (NIKDY jen "LIKE X || '%'") ošetřit.
    // Jméno kategorie je záměrně nesouvisející, ať cat_hit nenajde tenhle uzel i sám o sobě.
    Category unrelated = persistCategory("Umělé sladidlo", "branch-" + suffix + "-x", root);
    Product dairyProduct = persistProduct(dairy, "Dairy leaf " + suffix);
    Product unrelatedProduct = persistProduct(unrelated, "Unrelated leaf " + suffix);

    List<ProductSearchRow> rows = productRepository.search(criteria("mléčné"));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(dairyProduct.getId());
    assertThat(rows).extracting(ProductSearchRow::productId).doesNotContain(unrelatedProduct.getId());
  }

  @Test
  void keepsWordAndSemantics() {
    Category category = persistCategory();
    Product bioMleko = persistProduct(category, "Mléko bio " + UUID.randomUUID());
    Product justMleko = persistProduct(category, "Mléko " + UUID.randomUUID());

    List<ProductSearchRow> rows = productRepository.search(criteria("bio mleko"));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(bioMleko.getId());
    // Kdyby se místo tsvector použil holý LIKE nad core.norm_text, slovní AND by se ztratil
    // a "bio mleko" by nastalo i na samotné "Mléko" jako substring.
    assertThat(rows).extracting(ProductSearchRow::productId).doesNotContain(justMleko.getId());
  }

  @Test
  void explicitCategoryFilterNarrowsResults() {
    String suffix = UUID.randomUUID().toString();
    Category dairy = persistCategory("Mléčné výrobky", "mlecne-" + suffix, null);
    Category drugstore = persistCategory("Drogerie", "drogerie-" + suffix, null);
    Product product = persistProduct(dairy, "Mléko test " + suffix);

    List<ProductSearchRow> withMatchingFilter = productRepository.search(criteria("mléko", dairy.getId()));
    List<ProductSearchRow> withMismatchingFilter = productRepository.search(criteria("mléko", drugstore.getId()));

    assertThat(withMatchingFilter).extracting(ProductSearchRow::productId).contains(product.getId());
    // Filtr je AND nad nalezenými kandidáty, ne pátá OR větev — jinak by "Drogerie" vrátila
    // produkt jen proto, že název sedí na "mléko", i když je zařazený jinam.
    assertThat(withMismatchingFilter).extracting(ProductSearchRow::productId).doesNotContain(product.getId());
  }

  @Test
  void explicitCategoryFilterIncludesSubtree() {
    String suffix = UUID.randomUUID().toString();
    Category dairy = persistCategory("Mléčné výrobky", "mlecne-" + suffix, null);
    Category butter = persistCategory("Máslo", "maslo-" + suffix, dairy);
    String productName = "Filter subtree test " + suffix;
    Product product = persistProduct(butter, productName);

    // Filtr na RODIČE (dairy) musí najít i zboží zařazené do potomka (butter).
    List<ProductSearchRow> rows = productRepository.search(criteria(productName, dairy.getId()));

    assertThat(rows).extracting(ProductSearchRow::productId).contains(product.getId());
  }

  @Test
  void countMatchesSearch() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String word = "pocitadlo" + suffix;
    Category matchingCategory = persistCategory("Kategorie " + word, "cat-" + suffix, null);
    Category otherCategory = persistCategory();
    // Jeden kandidát z kategoriální větve, jeden ze jmenné — count() musí sedět na obojí.
    persistProduct(matchingCategory, "Product in category " + suffix);
    persistProduct(otherCategory, word + " product name " + suffix);

    ProductSearchCriteria bigPage = criteria(word, null, "cs", 1000);
    List<ProductSearchRow> rows = productRepository.search(bigPage);
    long count = productRepository.count(bigPage);

    assertThat(rows.size()).isGreaterThanOrEqualTo(2);
    assertThat((long) rows.size()).isEqualTo(count);
  }

  @Test
  void categoryMatchUsesLocalizedName() {
    String suffix = UUID.randomUUID().toString();
    Category category = persistCategory("Mléko", "mleko-" + suffix, null);
    categoryI18nRepository.saveAndFlush(
        CategoryI18n.builder().categoryId(category.getId()).locale("en").name("Milk").build());
    Product product = persistProduct(category, "Localized match test " + suffix);

    List<ProductSearchRow> viaEnglishName = productRepository.search(criteria("milk", null, "en"));
    List<ProductSearchRow> viaCzechFallbackOrSlug = productRepository.search(criteria("mleko", null, "cs"));
    List<ProductSearchRow> viaEnglishWordWrongLocale = productRepository.search(criteria("milk", null, "cs"));

    assertThat(viaEnglishName).extracting(ProductSearchRow::productId).contains(product.getId());
    assertThat(viaCzechFallbackOrSlug).extracting(ProductSearchRow::productId).contains(product.getId());
    // "milk" v locale="cs" nemá kde se vzít — core.category_i18n nemá řádek pro cs (zdroj je
    // core.category.name) a "Mléko"/slug "mleko-…" žádné "milk" neobsahují.
    assertThat(viaEnglishWordWrongLocale).extracting(ProductSearchRow::productId).doesNotContain(product.getId());
  }

  @Test
  void hiddenAndDraftStayInvisibleViaCategory() {
    String suffix = UUID.randomUUID().toString();
    Category category = persistCategory("Mléko", "mleko-" + suffix, null);
    AppUser draftAuthor = persistUser();
    AppUser viewer = persistUser();
    Product hidden = productRepository.saveAndFlush(Product.builder()
        .name("Hidden milk " + suffix).category(category).unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE).status(ProductStatus.ACTIVE)
        .hiddenAt(OffsetDateTime.now()).build());
    Product foreignDraft = productRepository.saveAndFlush(Product.builder()
        .name("Foreign draft milk " + suffix).category(category).unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE).status(ProductStatus.DRAFT)
        .createdByUserId(draftAuthor.getId()).build());
    Product visible = persistProduct(category, "Visible milk " + suffix);

    // viewer je cizí vůči foreignDraft (jiný app_user než draftAuthor).
    ProductSearchCriteria criteria = new ProductSearchCriteria(
        "mléko", null, null, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, viewer.getId(), "cs");
    List<ProductSearchRow> rows = productRepository.search(criteria);

    assertThat(rows).extracting(ProductSearchRow::productId).contains(visible.getId());
    // Kandidáti přišlí přes kategoriální větev (candidate) musí projít stejným filtrem
    // viditelnosti v matched jako kandidáti ze jmenné větve — hidden_at/cizí DRAFT se nesmí
    // "prosáknout" jen proto, že se JOIN kategorie na matched přesunul mimo WHERE.
    assertThat(rows).extracting(ProductSearchRow::productId).doesNotContain(hidden.getId(), foreignDraft.getId());
  }
}
