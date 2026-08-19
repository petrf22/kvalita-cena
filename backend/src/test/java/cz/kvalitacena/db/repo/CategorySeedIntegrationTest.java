package cz.kvalitacena.db.repo;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.CategoryI18n;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI guard nad seedem z {@code db/changelog/2026-08-19/01-category-seed.yaml} a
 * {@code 2026-08-20/01-category-tree.yaml} (docs/nasazeni.md, kap. 4; docs/lokalizace.md,
 * "Kategorie") — stejný duch jako {@code MessageBundleTest} u překladových bundlů, jen nad
 * číselníkem v DB místo nad `.properties`. Klíčová regrese, kterou má hlídat: až příště
 * přibude jazyk do {@code app.i18n.supported-locales}, tenhle test shodí build, dokud k němu
 * někdo nedopíše i řádky do {@code category-i18n.csv} — bez něj by nový jazyk potichu spadal
 * na český fallback jen u kategorií, ne u zbytku appky.
 *
 * <p>Běží nad reálným Postgresem (Testcontainers, vzor {@link RecordFlagRepositoryIntegrationTest}),
 * protože ověřuje DATA naseedovaná Liquibase migracemi při startu kontextu, ne logiku v Javě.
 */
@Testcontainers
@SpringBootTest
class CategorySeedIntegrationTest {

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
  private CategoryRepository categoryRepository;
  @Autowired
  private CategoryI18nRepository categoryI18nRepository;
  @Autowired
  private I18nProperties i18nProperties;

  @Test
  void pathMatchesParentChainForEveryCategory() {
    List<Category> categories = categoryRepository.findAll();
    Map<Long, Category> byId = new HashMap<>();
    categories.forEach(c -> byId.put(c.getId(), c));

    for (Category category : categories) {
      String expectedPath = category.getParent() == null
          ? category.getSlug()
          : byId.get(category.getParent().getId()).getPath() + "/" + category.getSlug();
      assertThat(category.getPath())
          .as("path kategorie '%s'", category.getSlug())
          .isEqualTo(expectedPath);
    }
  }

  @Test
  void treeHasNoCycleAndDepthIsAtMostThree() {
    List<Category> categories = categoryRepository.findAll();
    Map<Long, Category> byId = new HashMap<>();
    categories.forEach(c -> byId.put(c.getId(), c));

    for (Category category : categories) {
      Set<Long> visited = new HashSet<>();
      int depth = 1;
      Category current = category;
      while (current.getParent() != null) {
        boolean firstVisit = visited.add(current.getId());
        assertThat(firstVisit).as("cyklus ve stromu u '%s'", category.getSlug()).isTrue();
        current = byId.get(current.getParent().getId());
        depth++;
        assertThat(depth).as("hloubka stromu u '%s'", category.getSlug()).isLessThanOrEqualTo(3);
      }
    }
  }

  @Test
  void everyCategoryHasTranslationForEverySupportedLocaleExceptCzech() {
    List<Category> categories = categoryRepository.findAll();
    List<CategoryI18n> translations = categoryI18nRepository.findAll();

    Set<String> localesExceptCzech = new HashSet<>(i18nProperties.getSupportedLocales());
    localesExceptCzech.remove("cs");

    Map<Long, Set<String>> localesByCategory = new HashMap<>();
    for (CategoryI18n t : translations) {
      localesByCategory.computeIfAbsent(t.getCategoryId(), id -> new HashSet<>()).add(t.getLocale());
    }

    for (Category category : categories) {
      Set<String> present = localesByCategory.getOrDefault(category.getId(), Set.of());
      assertThat(present)
          .as("překlady kategorie '%s'", category.getSlug())
          .containsExactlyInAnyOrderElementsOf(localesExceptCzech);
    }
  }

  @Test
  void noCzechRowExistsInCategoryI18n() {
    assertThat(categoryI18nRepository.findAll())
        .extracting(CategoryI18n::getLocale)
        .doesNotContain("cs");
  }

  @Test
  void noOrphanedCategoryI18nRow() {
    Set<Long> categoryIds = new HashSet<>();
    categoryRepository.findAll().forEach(c -> categoryIds.add(c.getId()));

    assertThat(categoryI18nRepository.findAll())
        .allSatisfy(t -> assertThat(categoryIds)
            .as("osiřelý category_i18n řádek pro category_id=%s", t.getCategoryId())
            .contains(t.getCategoryId()));
  }
}
