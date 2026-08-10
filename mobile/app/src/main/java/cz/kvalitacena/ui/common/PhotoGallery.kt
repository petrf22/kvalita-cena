package cz.kvalitacena.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.Photo
import kotlinx.coroutines.launch

/**
 * Galerie fotek zboží/obchodu (core.media) — vodorovný pás náhledů, po klepnutí zvětšení
 * s prev/next navigací (vlastní Dialog, žádná další knihovna — stejný duch jako ruční Canvas
 * graf PriceChart.kt). Webový protějšek: frontend shared/photo-gallery.ts.
 */
@Composable
fun PhotoGallery(
  photos: List<Photo>,
  onPhotosChange: (List<Photo>) -> Unit,
  modifier: Modifier = Modifier,
) {
  var viewerIndex by remember { mutableStateOf<Int?>(null) }

  if (photos.isEmpty()) return

  LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(photos.size) { index ->
      val photo = photos[index]
      Box(
        modifier = Modifier
          .size(88.dp)
          .clip(RoundedCornerShape(6.dp))
          .clickable { viewerIndex = index },
      ) {
        AsyncImage(
          model = photo.thumbUrl(),
          contentDescription = photo.caption ?: stringResource(R.string.photo_alt),
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        if (photo.hidden) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .align(Alignment.BottomCenter)
              .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
          ) {
            Text(
              stringResource(R.string.photo_hidden_badge),
              color = MaterialTheme.colorScheme.onPrimary,
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }
    }
  }

  val index = viewerIndex
  if (index != null && index < photos.size) {
    PhotoViewerDialog(
      photos = photos,
      index = index,
      onIndexChange = { viewerIndex = it },
      onDismiss = { viewerIndex = null },
      onPhotosChange = onPhotosChange,
    )
  }
}

@Composable
private fun PhotoViewerDialog(
  photos: List<Photo>,
  index: Int,
  onIndexChange: (Int) -> Unit,
  onDismiss: () -> Unit,
  onPhotosChange: (List<Photo>) -> Unit,
) {
  val photo = photos[index]
  val scope = rememberCoroutineScope()
  var flagging by remember { mutableStateOf(false) }
  var actionMessage by remember { mutableStateOf<UiText?>(null) }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AsyncImage(
        model = photo.fullUrl(),
        contentDescription = photo.caption ?: stringResource(R.string.photo_alt),
        modifier = Modifier.fillMaxWidth().height(280.dp),
        contentScale = ContentScale.Fit,
      )
      photo.caption?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
      }

      if (photos.size > 1) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { if (index > 0) onIndexChange(index - 1) }, enabled = index > 0) {
            Text(stringResource(R.string.photo_prev))
          }
          Text("${index + 1} / ${photos.size}", style = MaterialTheme.typography.bodySmall)
          TextButton(
            onClick = { if (index < photos.size - 1) onIndexChange(index + 1) },
            enabled = index < photos.size - 1,
          ) { Text(stringResource(R.string.photo_next)) }
        }
      }

      Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (photo.mine) {
          if (index != 0) {
            OutlinedButton(onClick = {
              scope.launch {
                try {
                  val currentFirst = photos[0]
                  val updated = AppContainer.graphQlClient.updatePhoto(photo.id, null, 0)
                  val updatedFirst = AppContainer.graphQlClient.updatePhoto(currentFirst.id, null, 1)
                  val rest = photos.filter { it.id != photo.id && it.id != currentFirst.id }
                  onPhotosChange(listOf(updated, updatedFirst) + rest)
                  onIndexChange(0)
                } catch (e: Exception) {
                  actionMessage = e.toUiText()
                }
              }
            }) { Text(stringResource(R.string.photo_set_as_main)) }
          }
          OutlinedButton(onClick = {
            scope.launch {
              try {
                AppContainer.graphQlClient.deletePhoto(photo.id)
                onPhotosChange(photos.filter { it.id != photo.id })
                onDismiss()
              } catch (e: Exception) {
                actionMessage = e.toUiText()
              }
            }
          }) { Text(stringResource(R.string.common_delete)) }
        } else {
          OutlinedButton(
            onClick = {
              flagging = true
              scope.launch {
                try {
                  val result = AppContainer.graphQlClient.flagRecord("PHOTO", photo.id)
                  actionMessage = UiText.Res(
                    if (result.hidden) R.string.photo_report_hidden else R.string.report_acknowledged,
                  )
                } catch (e: Exception) {
                  actionMessage = e.toUiText()
                } finally {
                  flagging = false
                }
              }
            },
            enabled = !flagging,
          ) {
            if (flagging) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
              Text(stringResource(R.string.common_report))
            }
          }
        }
      }
      actionMessage?.let {
        Text(
          it.asString(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      Text(
        photo.attribution,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
