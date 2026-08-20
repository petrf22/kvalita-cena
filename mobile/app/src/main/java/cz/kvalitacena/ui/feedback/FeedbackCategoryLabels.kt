package cz.kvalitacena.ui.feedback

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R

/**
 * Popisky kategorie zpětné vazby (core.feedback.category) — stejný vzor jako
 * `ui/common/PriceKindLabels.kt`. `else ->` u neznámé hodnoty schválně (appka netypuje
 * `FeedbackCategory` z GraphQL schématu jako web, docs/lokalizace.md).
 */
@StringRes
private fun feedbackCategoryLabelRes(category: String): Int = when (category) {
  "BUG" -> R.string.feedback_category_bug
  "IDEA" -> R.string.feedback_category_idea
  "CONTENT" -> R.string.feedback_category_content
  "OTHER" -> R.string.feedback_category_other
  else -> R.string.feedback_category_other
}

@Composable
fun feedbackCategoryLabel(category: String): String = stringResource(feedbackCategoryLabelRes(category))

/** Nabídka v dropdownu — pořadí odpovídá pravděpodobné četnosti (chyba/nápad nejčastější). */
val FEEDBACK_CATEGORIES = listOf("BUG", "IDEA", "CONTENT", "OTHER")
