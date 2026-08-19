package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Category
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {

  private val categories = listOf(
    Category(id = "1", name = "Potraviny", slug = "potraviny", path = "potraviny", sortOrder = 10),
    Category(id = "2", name = "Nápoje", slug = "napoje", path = "napoje", sortOrder = 20),
    Category(id = "3", name = "Mléčné výrobky", slug = "mlecne", path = "potraviny/mlecne", sortOrder = 10),
    Category(id = "4", name = "Pečivo", slug = "pecivo", path = "potraviny/pecivo", sortOrder = 20),
    Category(id = "5", name = "Máslo", slug = "maslo", path = "potraviny/mlecne/maslo", sortOrder = 10),
    Category(id = "6", name = "Sýry", slug = "syry", path = "potraviny/mlecne/syry", sortOrder = 20),
  )

  @Test
  fun ordersDepthFirstByCuratedSortOrderNotAlphabeticalPath() {
    val order = buildCategoryTreeOrder(categories, Locale.forLanguageTag("cs"))
    assertEquals(
      listOf("Potraviny", "Mléčné výrobky", "Máslo", "Sýry", "Pečivo", "Nápoje"),
      order.map { it.category.name },
    )
  }

  @Test
  fun depthReflectsPositionInTree() {
    val order = buildCategoryTreeOrder(categories, Locale.forLanguageTag("cs"))
    val depthByName = order.associate { it.category.name to it.depth }
    assertEquals(0, depthByName["Potraviny"])
    assertEquals(1, depthByName["Mléčné výrobky"])
    assertEquals(2, depthByName["Máslo"])
    assertEquals(0, depthByName["Nápoje"])
  }

  @Test
  fun siblingsSortByCollatorNotDefaultStringCompare() {
    val czech = listOf(
      Category(id = "1", name = "Žvýkačky", slug = "a", path = "a", sortOrder = 10),
      Category(id = "2", name = "Čokoláda", slug = "b", path = "b", sortOrder = 10),
      Category(id = "3", name = "Ananas", slug = "c", path = "c", sortOrder = 10),
    )
    val order = buildCategoryTreeOrder(czech, Locale.forLanguageTag("cs"))
    assertEquals(listOf("Ananas", "Čokoláda", "Žvýkačky"), order.map { it.category.name })
  }

  @Test
  fun breadcrumbJoinsWholeAncestorChain() {
    val maslo = categories.first { it.name == "Máslo" }
    assertEquals("Potraviny › Mléčné výrobky › Máslo", categoryBreadcrumb(maslo, categories))
  }

  @Test
  fun breadcrumbOfRootIsJustItsOwnName() {
    val potraviny = categories.first { it.name == "Potraviny" }
    assertEquals("Potraviny", categoryBreadcrumb(potraviny, categories))
  }

  @Test
  fun filterMatchesCaseInsensitivelyAndIgnoresBlankQuery() {
    assertEquals(emptyList<Category>(), filterCategories("  ", categories))
    assertEquals(listOf("Máslo"), filterCategories("mÁs", categories).map { it.name })
  }

  @Test
  fun choicesWithBlankQueryUseTreeOrderWithIndentedLabels() {
    val choices = categoryChoicesFor("", categories, Locale.forLanguageTag("cs"))
    assertEquals(
      listOf("Potraviny", "    Mléčné výrobky", "        Máslo", "        Sýry", "    Pečivo", "Nápoje"),
      choices.map { it.label },
    )
  }

  @Test
  fun choicesWithQueryUseBreadcrumbLabels() {
    val choices = categoryChoicesFor("ásl", categories, Locale.forLanguageTag("cs"))
    assertEquals(listOf("Potraviny › Mléčné výrobky › Máslo"), choices.map { it.label })
    assertEquals("5", choices.single().category.id)
  }
}
