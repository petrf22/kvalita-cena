package cz.kvalitacena.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R

/**
 * Známka kvality jako ve škole — 1 nejlepší, 5 nejhorší (viz zadání). Pod
 * [MIN_RATINGS_FOR_BADGE] hodnoceními se ukazuje jako "orientační", obdoba pravidla
 * n_eff < 2 u cen (docs/reputace.md) — práh drží i backend v app.quality.min-ratings-for-badge.
 */
private const val MIN_RATINGS_FOR_BADGE = 3

@Composable
fun QualityBadge(average: Double?, count: Int, modifier: Modifier = Modifier) {
  val text = if (average == null) {
    stringResource(R.string.quality_not_rated)
  } else if (count in 1 until MIN_RATINGS_FOR_BADGE) {
    stringResource(R.string.quality_rated_approximate, average)
  } else {
    stringResource(R.string.quality_rated, average)
  }
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = if (average == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    modifier = modifier,
  )
}
