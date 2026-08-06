package cz.kvalitacena.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Známka kvality jako ve škole — 1 nejlepší, 5 nejhorší (viz zadání). Pod
 * [MIN_RATINGS_FOR_BADGE] hodnoceními se ukazuje jako "orientační", obdoba pravidla
 * n_eff < 2 u cen (docs/reputace.md) — práh drží i backend v app.quality.min-ratings-for-badge.
 */
private const val MIN_RATINGS_FOR_BADGE = 3

@Composable
fun QualityBadge(average: Double?, count: Int, modifier: Modifier = Modifier) {
  val text = if (average == null) {
    "Kvalita: zatím nehodnoceno"
  } else {
    val base = "Kvalita %.1f/5".format(average)
    if (count in 1 until MIN_RATINGS_FOR_BADGE) "$base (orientační)" else base
  }
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = if (average == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    modifier = modifier,
  )
}
