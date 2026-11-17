package cz.kvalitacena.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1677FF)

private val LightColors = lightColorScheme(primary = Blue)

// Výchozí tmavé onPrimary (#381E72) počítá s pastelovou primární barvou — na syté značkové
// modré dávalo text tlačítka s kontrastem ~3,2 : 1. Bílá jako ve světlém schématu (docs/design.md).
private val DarkColors = darkColorScheme(primary = Blue, onPrimary = Color.White)

@Composable
fun KvalitaACenaTheme(content: @Composable () -> Unit) {
  val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
  MaterialTheme(colorScheme = colors, content = content)
}
