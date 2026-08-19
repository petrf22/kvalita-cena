package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Category
import java.text.Collator
import java.util.Locale

/**
 * Zrcadlo webového `shared/category-tree.ts` — viz tam pro celkové odůvodnění. `Query.categories`
 * vrací plochý seznam řazený podle `path` (abecedně podle slugu), hierarchii nese `path` samo
 * (řetězec slugů oddělený „/") a pořadí SOUROZENCŮ v jedné větvi `sortOrder` — appka si strom
 * skládá sama, ne server. Čistá logika mimo Compose (JUnit, ne instrumentace), stejný vzor jako
 * `PublicationStatusText.kt`/`PriceRowValidation.kt`. Používá `ui/product/ProductFormScreen.kt`
 * přes generický `SearchableDropdown` (dokud uživatel nic nenapsal, nabídne strom v pořadí
 * stromu s odsazením podle hloubky; při psaní přepne na plochý filtr s breadcrumb popiskem).
 */
data class CategoryTreeItem(val category: Category, val depth: Int)

private fun parentPath(path: String): String? {
  val idx = path.lastIndexOf('/')
  return if (idx == -1) null else path.substring(0, idx)
}

private fun siblingComparator(locale: Locale): Comparator<Category> {
  val collator = Collator.getInstance(locale)
  return compareBy<Category> { it.sortOrder }.thenComparator { a, b -> collator.compare(a.name, b.name) }
}

/**
 * Poskládá plochý seznam do pořadí stromu (rodič, pak potomci seřazení podle (sortOrder, name),
 * rekurzivně) s dopočítanou hloubkou pro odsazení. Vnitřní uzly (mají potomky) jsou v seznamu
 * stejně vybíratelné jako listy — kdo pro své zboží nenajde podkategorii, zvolí nadřazenou místo
 * aby zakládání vzdal (docs/nasazeni.md, kap. 4, "Než pozvat první lidi").
 */
fun buildCategoryTreeOrder(
  categories: List<Category>,
  locale: Locale = Locale.getDefault(),
): List<CategoryTreeItem> {
  val comparator = siblingComparator(locale)
  val byPath = categories.associateBy { it.path }
  val childrenByParentPath = mutableMapOf<String, MutableList<Category>>()
  val roots = mutableListOf<Category>()
  for (category in categories) {
    val parent = parentPath(category.path)
    if (parent != null && byPath.containsKey(parent)) {
      childrenByParentPath.getOrPut(parent) { mutableListOf() }.add(category)
    } else {
      roots.add(category)
    }
  }

  val result = mutableListOf<CategoryTreeItem>()
  fun visit(category: Category, depth: Int) {
    result.add(CategoryTreeItem(category, depth))
    childrenByParentPath[category.path]?.sortedWith(comparator)?.forEach { visit(it, depth + 1) }
  }
  roots.sortedWith(comparator).forEach { visit(it, 0) }
  return result
}

/**
 * Popisek pro řádek při hledání ("Potraviny › Mléčné výrobky › Máslo") — samotný list ("Máslo")
 * by bez zbytku větve nebylo poznat, pod čím v číselníku leží.
 */
fun categoryBreadcrumb(category: Category, categories: List<Category>): String {
  val byPath = categories.associateBy { it.path }
  val chain = mutableListOf(category.name)
  var current: Category? = category
  while (true) {
    val parent = parentPath(current!!.path)?.let { byPath[it] } ?: break
    chain.add(0, parent.name)
    current = parent
  }
  return chain.joinToString(" › ")
}

/** Filtr pro režim hledání v `SearchableDropdown` — case-insensitive podle jména kategorie. */
fun filterCategories(query: String, categories: List<Category>): List<Category> {
  if (query.isBlank()) return emptyList()
  val needle = query.trim()
  return categories.filter { it.name.contains(needle, ignoreCase = true) }
}

private const val TREE_INDENT_PER_LEVEL = "    "

/** Řádek nabídnutý ve `SearchableDropdown<CategoryChoice>` — [label] nese buď odsazení podle
 *  hloubky (dokud uživatel nic nenapsal), nebo breadcrumb celé větve (při hledání). */
data class CategoryChoice(val category: Category, val label: String)

/**
 * Sestaví nabídku pro `SearchableDropdown` (ui/product/ProductFormScreen.kt): prázdný dotaz
 * ukáže celý strom v pořadí sortOrder s odsazením podle hloubky, jinak plochý filtr podle
 * jména s breadcrumb popiskem. `locale` volající bere z Compose kontextu
 * (`LocalConfiguration.current.locales[0]`, ne `Locale.getDefault()` — stejný důvod jako
 * RelativeDate.kt/Money.kt), aby řazení sourozenců reagovalo na přepnutí jazyka za běhu.
 */
fun categoryChoicesFor(
  query: String,
  categories: List<Category>,
  locale: Locale = Locale.getDefault(),
): List<CategoryChoice> =
  if (query.isBlank()) {
    buildCategoryTreeOrder(categories, locale).map {
      CategoryChoice(it.category, TREE_INDENT_PER_LEVEL.repeat(it.depth) + it.category.name)
    }
  } else {
    filterCategories(query, categories).map { CategoryChoice(it, categoryBreadcrumb(it, categories)) }
  }
