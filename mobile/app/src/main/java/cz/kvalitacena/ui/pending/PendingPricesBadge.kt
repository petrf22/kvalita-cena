package cz.kvalitacena.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Dotykový cíl podle docs/design.md („Minimální dotykový cíl je 44 px").
private val MIN_TOUCH_HEIGHT = 44.dp

/**
 * Počty fronty pro odznak. Přihlášení z `AuthRepository.isLoggedIn` (nikdy z přítomnosti
 * access tokenu — kořenový CLAUDE.md), `publicUid` z Keystore mimo hlavní vlákno: bez něj by
 * cena jiného účtu vypadala jako obyčejně čekající.
 */
@Composable
fun rememberPendingPriceSummary(): PendingPriceSummary {
  val pending by AppContainer.pendingPrices.pending.collectAsState()
  val loggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  val currentUid by produceState<String?>(initialValue = null, loggedIn) {
    value = if (loggedIn) withContext(Dispatchers.IO) { AppContainer.authRepository.currentUserUid() } else null
  }
  return remember(pending, loggedIn, currentUid) { pendingPriceSummary(pending, loggedIn, currentUid) }
}

/**
 * Vstup na „Čekající ceny" — jen když fronta není prázdná ([pendingPriceBadgeText] vrací `null`
 * a nekreslí se nic). Položky, které bez uživatele neodejdou, mají `errorContainer`, jen čekající
 * `secondaryContainer` jako pruh offline: čekání chybou není. Plné (neprůhledné) barvy podkladu
 * jsou čitelné i přes náhled kamery na záložce Sken, v obou režimech.
 */
@Composable
fun PendingPricesBadge(summary: PendingPriceSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val text = pendingPriceBadgeText(summary) ?: return
  val attention = summary.needsAttention > 0
  Surface(
    onClick = onClick,
    color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.heightIn(min = MIN_TOUCH_HEIGHT),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(text.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
      Text(stringResource(R.string.pending_prices_badge_open), style = MaterialTheme.typography.titleSmall)
    }
  }
}
