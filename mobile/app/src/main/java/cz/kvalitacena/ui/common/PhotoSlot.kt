package cz.kvalitacena.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R

/**
 * Jeden slot na fotku PŘED uložením záznamu — appka soubor jen podrží jako [Uri], nahrání
 * zajistí volající obrazovka až po vzniku záznamu (docs/datovy-model.md, "fotky se nahrávají
 * výhradně na existující záznam"). Na rozdíl od [PhotoPicker], který je vázaný na existující
 * `recordId` a nahrává rovnou. Použití: `ui/product/ProductFormScreen.kt` (fotka zboží +
 * fotka etikety). Sdílí `createCameraOutputUri`/`sizeOf` z PhotoPicker.kt a validaci z
 * PhotoValidation.kt.
 */
@Composable
fun PhotoSlot(
  label: String,
  onUriChange: (Uri?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var uri by remember { mutableStateOf<Uri?>(null) }
  var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
  var error by remember { mutableStateOf<UiText?>(null) }
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val isLoggedIn = accessToken != null

  fun setUri(value: Uri?) {
    uri = value
    onUriChange(value)
  }

  fun tryUri(candidate: Uri) {
    val mimeType = context.contentResolver.getType(candidate)
    // Slot drží nejvýš jednu fotku, appka volá s existujícím počtem 0 — limit počtu na záznam
    // se sem netýká, stejný kód jako u PhotoPicker jen ošetří formát/velikost.
    val validationError = when (photoValidationError(mimeType, sizeOf(context, candidate), 0)) {
      PhotoValidationError.UNSUPPORTED_FORMAT -> UiText.Res(R.string.photo_unsupported_format)
      PhotoValidationError.TOO_LARGE -> UiText.Res(R.string.photo_too_large)
      PhotoValidationError.LIMIT_REACHED, null -> null
    }
    if (validationError != null) {
      error = validationError
      return
    }
    error = null
    setUri(candidate)
  }

  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
    val pending = pendingCameraUri
    if (success && pending != null) tryUri(pending)
  }
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
    picked?.let { tryUri(it) }
  }

  Column(modifier = modifier) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    if (!isLoggedIn) {
      Text(
        stringResource(R.string.product_form_photo_login_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val current = uri
      if (current != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          AsyncImage(
            model = current,
            contentDescription = label,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
          )
          OutlinedButton(onClick = { error = null; setUri(null) }) {
            Text(stringResource(R.string.photo_remove))
          }
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {
            val newUri = createCameraOutputUri(context)
            pendingCameraUri = newUri
            cameraLauncher.launch(newUri)
          }) { Text(stringResource(R.string.photo_take_picture)) }
          OutlinedButton(onClick = {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
          }) { Text(stringResource(R.string.photo_pick_from_gallery)) }
        }
      }
    }
    error?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}
