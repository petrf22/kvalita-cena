package cz.kvalitacena.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cz.kvalitacena.R
import cz.kvalitacena.network.ExternalProductImage
import cz.kvalitacena.network.Photo

/**
 * Priorita zdroje obrázku zboží: vlastní fotka > obrázek z Open Food Facts > žádný. Jedno
 * místo pro [ProductThumb] i [ProductImagePreview], stejné pravidlo jako na detailu zboží
 * (ProductDetailScreen.kt) a na webu (shared/product-thumb.ts).
 *
 * `full = true` vrací plnou verzi (velký náhled), jinak miniaturu do řádku seznamu.
 * `externalImage.url`/`thumbnailUrl` jsou (na rozdíl od [Photo.fullUrl]/[Photo.thumbUrl]) už
 * plné externí URL — OFF obrázky se neukládají do core.media, appka je nikdy neprefixuje
 * ApiConfig.BASE_URL.
 */
fun productImageUrl(photos: List<Photo>, externalImage: ExternalProductImage?, full: Boolean = false): String? {
  val photo = photos.firstOrNull()
  if (photo != null) return if (full) photo.fullUrl() else photo.thumbUrl()
  return if (full) externalImage?.url else externalImage?.thumbnailUrl
}

/**
 * Zobrazený obrázek pochází z Open Food Facts, ne z vlastní fotky — volající MUSÍ vedle něj
 * uvést atribuci zdroje (ODbL, docs/datovy-model.md). Web protějšek:
 * `usesExternalImageFallback` v shared/product-thumb.ts.
 */
fun usesExternalImage(photos: List<Photo>, externalImage: ExternalProductImage?): Boolean =
  photos.isEmpty() && externalImage != null

/**
 * Miniatura zboží pro řádek výsledků hledání — priorita zdroje [productImageUrl], bez obrázku
 * zástupná ikona (drží zarovnání řádků). Web protějšek: shared/product-thumb.ts.
 */
@Composable
fun ProductThumb(
  name: String,
  photos: List<Photo>,
  externalImage: ExternalProductImage?,
  modifier: Modifier = Modifier,
  size: Dp = 36.dp,
) {
  val url = productImageUrl(photos, externalImage)

  if (url != null) {
    AsyncImage(
      model = url,
      contentDescription = name,
      contentScale = ContentScale.Crop,
      modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)),
    )
  } else {
    Box(
      modifier = modifier
        .size(size)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_picture),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size * 0.5f),
      )
    }
  }
}

/**
 * Velký náhled zboží nad názvem (obrazovka zápisu ceny po skenu) — stejná priorita zdroje jako
 * [ProductThumb], ale plná URL místo miniatury (na plné šířce by byl thumbnail rozmazaný) a
 * `ContentScale.Fit`, ať se neořezávají obaly s různým poměrem stran.
 *
 * Bez obrázku nevykreslí NIC (na rozdíl od [ProductThumb]) — velký prázdný rámeček s ikonou by
 * jako hlavní prvek obrazovky jen odsunul ceny pod ohyb.
 */
@Composable
fun ProductImagePreview(
  name: String,
  photos: List<Photo>,
  externalImage: ExternalProductImage?,
  modifier: Modifier = Modifier,
  height: Dp = 180.dp,
) {
  val url = productImageUrl(photos, externalImage, full = true) ?: return

  AsyncImage(
    model = url,
    contentDescription = name,
    contentScale = ContentScale.Fit,
    modifier = modifier
      .height(height)
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  )
}
