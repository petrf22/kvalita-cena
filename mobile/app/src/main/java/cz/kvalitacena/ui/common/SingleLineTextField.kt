package cz.kvalitacena.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Jednořádkový vstup pro celou appku — dosavadní `OutlinedTextField` neměly `singleLine`, takže
 * Enter vkládal nový řádek místo potvrzení a dlouhý text pole natahoval do výšky (viz zadání,
 * bod 7). `singleLine = true` implikuje `maxLines = 1`, nastavovat obojí je zbytečné.
 */
@Composable
fun SingleLineTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  readOnly: Boolean = false,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  trailingIcon: (@Composable () -> Unit)? = null,
  isError: Boolean = false,
  // Chybová/nápovědní hláška pod polem — doteď šly chyby jen jako samostatný Text nad
  // formulářem (viz PriceEntryScreen), což se nehodí k poli, kterého se konkrétně týkají.
  supportingText: (@Composable () -> Unit)? = null,
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    modifier = modifier,
    readOnly = readOnly,
    singleLine = true,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    trailingIcon = trailingIcon,
    isError = isError,
    supportingText = supportingText,
    visualTransformation = visualTransformation,
  )
}
