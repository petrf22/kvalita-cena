package cz.kvalitacena.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import kotlin.math.round

/**
 * Hvězdičky 1–5 (5 nejlepší) — web protějšek shared/quality-badge.ts a nz-rate na
 * product-detail-page.ts (docs/reputace.md, otočeno ze školní známky po testování). Compose
 * Material knihovna ikon (material-icons-extended) v projektu není závislostí, takže půlhvězda
 * jde přes vlastní vektorové XML (ic_star/ic_star_half/ic_star_border v res/drawable, stejný
 * vzor jako ic_tab_*), ne přes ořezávání jedné ikony.
 */
private val StarColor = Color(0xFFFAAD14)

/** Jen pro čtení — průměr zaokrouhlený na půlku hvězdy, stejná logika jako web
 *  (`Math.round(average * 2) / 2` v shared/quality-badge.ts). */
@Composable
fun StarRatingDisplay(average: Double?, modifier: Modifier = Modifier, starSize: Dp = 16.dp) {
  val rounded = average?.let { round(it * 2) / 2.0 } ?: 0.0
  Row(modifier = modifier) {
    for (position in 1..5) {
      val icon = when {
        rounded >= position -> R.drawable.ic_star
        rounded >= position - 0.5 -> R.drawable.ic_star_half
        else -> R.drawable.ic_star_border
      }
      Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = StarColor,
        modifier = Modifier.size(starSize),
      )
    }
  }
}

/** Interaktivní zadání — ťuknutí na hvězdu N nastaví hodnocení na N. Žádné mazání (API ho
 *  nepodporuje) — stejné jako web `[nzAllowClear]="false"`. */
@Composable
fun StarRatingInput(
  value: Int?,
  onRate: (Int) -> Unit,
  modifier: Modifier = Modifier,
  starSize: Dp = 32.dp,
) {
  Row(modifier = modifier) {
    for (position in 1..5) {
      val filled = value != null && value >= position
      Icon(
        painter = painterResource(if (filled) R.drawable.ic_star else R.drawable.ic_star_border),
        contentDescription = stringResource(R.string.quality_rate_stars, position),
        tint = StarColor,
        modifier = Modifier.size(starSize).clickable { onRate(position) },
      )
    }
  }
}
