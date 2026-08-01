package cz.kvalitacena.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1677FF)

private val LightColors = lightColorScheme(primary = Blue)
private val DarkColors = darkColorScheme(primary = Blue)

@Composable
fun KvalitaACenaTheme(content: @Composable () -> Unit) {
  val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
  MaterialTheme(colorScheme = colors, content = content)
}
