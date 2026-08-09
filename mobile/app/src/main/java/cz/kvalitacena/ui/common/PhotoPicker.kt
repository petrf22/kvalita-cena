package cz.kvalitacena.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.Photo
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tlačítka "Vyfotit"/"Vybrat z galerie" + nahrání (core.media). Systémový intent místo
 * vlastního kamerového UI (na rozdíl od skenování čárových kódů, ui/scan/ScanScreen.kt) — je
 * to řádově míň kódu a fotku balení to nijak UX nezhoršuje. `PickVisualMedia` (systémový Photo
 * Picker) nepotřebuje žádné nové oprávnění, na rozdíl od `READ_MEDIA_IMAGES`. Klientská
 * validace je jen včasná zpětná vazba — backend (ImageProcessingService) validuje znovu
 * a přísněji, viz PhotoValidation.kt.
 */
@Composable
fun PhotoPicker(
  recordType: String,
  recordId: String,
  existingPhotoCount: Int,
  onUploaded: (Photo) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var uploading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

  fun upload(uri: Uri) {
    val mimeType = context.contentResolver.getType(uri)
    val validationError = photoValidationError(mimeType, sizeOf(context, uri), existingPhotoCount)
    if (validationError != null) {
      error = validationError
      return
    }
    uploading = true
    error = null
    scope.launch {
      try {
        val photo = AppContainer.mediaClient.upload(context, recordType, recordId, uri)
        onUploaded(photo)
      } catch (e: Exception) {
        error = "Nahrání fotky se nepovedlo, zkus to prosím znovu."
      } finally {
        uploading = false
      }
    }
  }

  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
    val uri = pendingCameraUri
    if (success && uri != null) upload(uri)
  }
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let { upload(it) }
  }

  Column(modifier = modifier) {
    if (existingPhotoCount >= MAX_PHOTOS_PER_RECORD) {
      Text(
        "Záznam už má maximální počet fotek.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = {
            val uri = createCameraOutputUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
          },
          enabled = !uploading,
        ) { Text("Vyfotit") }
        OutlinedButton(
          onClick = {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
          },
          enabled = !uploading,
        ) { Text("Vybrat z galerie") }
        if (uploading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
      }
    }
    error?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/** Dočasný soubor pod cache adresářem appky, sdílený systémové kameře přes FileProvider (viz AndroidManifest.xml). */
private fun createCameraOutputUri(context: Context): Uri {
  val dir = File(context.cacheDir, "captured_photos").apply { mkdirs() }
  val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** OpenableColumns.SIZE přes Cursor — spolehlivější než InputStream.available(), které u content:// URI nic nezaručuje. */
private fun sizeOf(context: Context, uri: Uri): Long {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
      return cursor.getLong(sizeIndex)
    }
  }
  return 0L
}
