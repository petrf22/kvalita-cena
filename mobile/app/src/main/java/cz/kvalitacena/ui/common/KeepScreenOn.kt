package cz.kvalitacena.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Displej nezhasne, dokud je tahle část UI v kompozici — snímání dlouhé účtenky na víc dílů
 * trvá déle, než bývá systémový limit, a zhasnutí uprostřed by snímání přerušilo. Jen příznak
 * okna (žádný `WakeLock`, žádné oprávnění), po odchodu se vrátí na původní hodnotu.
 */
@Composable
fun KeepScreenOn() {
  val view = LocalView.current
  DisposableEffect(view) {
    val before = view.keepScreenOn
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = before }
  }
}
