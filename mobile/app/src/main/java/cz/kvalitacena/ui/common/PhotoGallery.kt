package cz.kvalitacena.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.ExternalProductImage
import cz.kvalitacena.network.Photo
import kotlinx.coroutines.launch

/**
 * Jedna položka galerie. Vlastní fotka ([Own], `core.media`) se dá nastavit jako hlavní, smazat
 * a nahlásit; obrázek z Open Food Facts ([External]) je jen ke koukání — nemá `id`, appka ho
 * needituje ani nekopíruje k sobě (ODbL, docs/datovy-model.md), povinná je u něj jen atribuce.
 */
sealed interface GalleryImage {
  val thumbUrl: String
  val fullUrl: String
  val attribution: String
  val caption: String?

  data class Own(val photo: Photo) : GalleryImage {
    override val thumbUrl get() = photo.thumbUrl()
    override val fullUrl get() = photo.fullUrl()
    override val attribution get() = photo.attribution
    override val caption get() = photo.caption
  }

  data class External(val image: ExternalProductImage) : GalleryImage {
    // Obrázky z OFF jsou už plné externí URL — na rozdíl od Photo se neprefixují BASE_URL.
    override val thumbUrl get() = image.thumbnailUrl
    override val fullUrl get() = image.url
    override val attribution get() = image.attribution
    override val caption: String? get() = null
  }
}

/**
 * Galerie fotek zboží/obchodu — vodorovný pás náhledů, po klepnutí prohlížeč přes celou
 * obrazovku s listováním prstem a zoomem (vlastní Dialog, žádná další knihovna — stejný duch
 * jako ruční Canvas graf PriceChart.kt). Webový protějšek: frontend shared/photo-gallery.ts,
 * ten zvětšení na celou obrazovku zatím nemá.
 *
 * [externalImages] jsou obrázky obalu a etikety z Open Food Facts (`Product.externalImages`) —
 * řadí se ZA vlastní fotky, protože komunitní obsah má přednost, a u zboží, které vlastní fotku
 * ještě nemá, jsou jediné, co jde ukázat. Detail obchodu je nepředává.
 */
@Composable
fun PhotoGallery(
  photos: List<Photo>,
  onPhotosChange: (List<Photo>) -> Unit,
  modifier: Modifier = Modifier,
  externalImages: List<ExternalProductImage> = emptyList(),
) {
  var viewerIndex by remember { mutableStateOf<Int?>(null) }

  val items = remember(photos, externalImages) {
    photos.map { GalleryImage.Own(it) } + externalImages.map { GalleryImage.External(it) }
  }
  if (items.isEmpty()) return

  LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items.size) { index ->
      val item = items[index]
      Box(
        modifier = Modifier
          .size(88.dp)
          .clip(RoundedCornerShape(6.dp))
          .clickable { viewerIndex = index },
      ) {
        AsyncImage(
          model = item.thumbUrl,
          contentDescription = item.caption ?: stringResource(R.string.photo_alt),
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        // Zdroj MUSÍ být vidět už v pásu, ne až v prohlížeči (ODbL, kořenový CLAUDE.md);
        // u skryté fotky týmž způsobem informace, proč ji vidí jen autor.
        val badge = when {
          item is GalleryImage.Own && item.photo.hidden -> stringResource(R.string.photo_hidden_badge)
          item is GalleryImage.External -> stringResource(R.string.photo_source_off)
          else -> null
        }
        badge?.let {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .align(Alignment.BottomCenter)
              .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
          ) {
            Text(
              it,
              color = MaterialTheme.colorScheme.onPrimary,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }
    }
  }

  val index = viewerIndex
  if (index != null && index < items.size) {
    PhotoViewerDialog(
      items = items,
      initialIndex = index,
      onDismiss = { viewerIndex = null },
      onPhotosChange = onPhotosChange,
      photos = photos,
    )
  }
}

/**
 * Prohlížeč přes celou obrazovku: fotky se listují prstem ([HorizontalPager]), zvětšují prsty
 * i dvojklikem ([ZoomableImage]) a posouvají tažením. Na černém pozadí bez ohledu na téma
 * appky — světlý podklad by u fotky obalu měnil dojem z barev.
 *
 * Tlačítka Předchozí/Další zůstávají vedle listování prstem kvůli TalkBacku; gesto se čtečkou
 * obrazovky spolehlivě neudělá.
 */
@Composable
private fun PhotoViewerDialog(
  items: List<GalleryImage>,
  initialIndex: Int,
  onDismiss: () -> Unit,
  onPhotosChange: (List<Photo>) -> Unit,
  photos: List<Photo>,
) {
  val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
  var zoomed by remember { mutableStateOf(false) }
  val index = pagerState.currentPage.coerceIn(items.indices)
  val item = items[index]
  val scope = rememberCoroutineScope()
  var flagging by remember { mutableStateOf(false) }
  var actionMessage by remember { mutableStateOf<UiText?>(null) }

  // Přechod na jinou fotku zahazuje zprávu o akci i zámek listování — ten patří k té fotce,
  // na které se zvětšovalo, a bez resetu by po přepnutí zůstal viset.
  LaunchedEffect(index) {
    zoomed = false
    actionMessage = null
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      HorizontalPager(
        state = pagerState,
        // Zvětšenou fotku uživatel posouvá, ne listuje — jinak by tažení přeplo stránku.
        userScrollEnabled = !zoomed,
        modifier = Modifier.fillMaxSize(),
      ) { page ->
        ZoomableImage(
          model = items[page].fullUrl,
          contentDescription = items[page].caption ?: stringResource(R.string.photo_alt),
          onZoomedChange = { if (page == pagerState.currentPage) zoomed = it },
          modifier = Modifier.fillMaxSize(),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onDismiss) {
          Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.photo_close),
            tint = Color.White,
          )
        }
        if (items.size > 1) {
          Text(
            "${index + 1} / ${items.size}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(end = 12.dp),
          )
        }
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.BottomCenter)
          .background(Color.Black.copy(alpha = 0.5f))
          .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item.caption?.let {
          Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }

        if (items.size > 1) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            TextButton(
              onClick = { scope.launch { pagerState.animateScrollToPage(index - 1) } },
              enabled = index > 0,
            ) { Text(stringResource(R.string.photo_prev)) }
            TextButton(
              onClick = { scope.launch { pagerState.animateScrollToPage(index + 1) } },
              enabled = index < items.size - 1,
            ) { Text(stringResource(R.string.photo_next)) }
          }
        }

        if (item is GalleryImage.Own) {
          PhotoActions(
            photo = item.photo,
            photos = photos,
            onPhotosChange = onPhotosChange,
            onDismiss = onDismiss,
            onMovedToFront = { scope.launch { pagerState.scrollToPage(0) } },
            flagging = flagging,
            onFlaggingChange = { flagging = it },
            onActionMessage = { actionMessage = it },
          )
        }

        actionMessage?.let {
          Text(
            it.asString(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        Text(
          item.attribution,
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }
}

/**
 * Akce nad vlastní fotkou z `core.media` — vlastník ji smí povýšit na hlavní a smazat, ostatní
 * nahlásit (práh skrytí fotky je mnohem nižší než u katalogu, docs/reputace.md).
 */
@Composable
private fun PhotoActions(
  photo: Photo,
  photos: List<Photo>,
  onPhotosChange: (List<Photo>) -> Unit,
  onDismiss: () -> Unit,
  onMovedToFront: () -> Unit,
  flagging: Boolean,
  onFlaggingChange: (Boolean) -> Unit,
  onActionMessage: (UiText) -> Unit,
) {
  val scope = rememberCoroutineScope()

  Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (photo.mine) {
      if (photos.firstOrNull()?.id != photo.id) {
        OutlinedButton(onClick = {
          scope.launch {
            try {
              val currentFirst = photos[0]
              val updated = AppContainer.graphQlClient.updatePhoto(photo.id, null, 0)
              val updatedFirst = AppContainer.graphQlClient.updatePhoto(currentFirst.id, null, 1)
              val rest = photos.filter { it.id != photo.id && it.id != currentFirst.id }
              onPhotosChange(listOf(updated, updatedFirst) + rest)
              onMovedToFront()
            } catch (e: Exception) {
              onActionMessage(e.toUiText())
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
            onActionMessage(e.toUiText())
          }
        }
      }) { Text(stringResource(R.string.common_delete)) }
    } else {
      OutlinedButton(
        onClick = {
          onFlaggingChange(true)
          scope.launch {
            try {
              val result = AppContainer.graphQlClient.flagRecord("PHOTO", photo.id)
              onActionMessage(
                UiText.Res(
                  if (result.hidden) R.string.photo_report_hidden else R.string.report_acknowledged,
                ),
              )
            } catch (e: Exception) {
              onActionMessage(e.toUiText())
            } finally {
              onFlaggingChange(false)
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
}
