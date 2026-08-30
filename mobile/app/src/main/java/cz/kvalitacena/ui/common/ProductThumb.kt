package cz.kvalitacena.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * Miniatura zboží pro řádek výsledků hledání — priorita zdroje: vlastní fotka > obrázek
 * z Open Food Facts > zástupná ikona. Stejné pravidlo jako na detailu zboží
 * (ProductDetailScreen.kt). Web protějšek: shared/product-thumb.ts.
 *
 * `externalImage.thumbnailUrl` je (na rozdíl od [Photo.thumbUrl]) už plná externí URL — OFF
 * obrázky se neukládají do core.media, appka je nikdy neprefixuje ApiConfig.BASE_URL.
 */
@Composable
fun ProductThumb(
  name: String,
  photos: List<Photo>,
  externalImage: ExternalProductImage?,
  modifier: Modifier = Modifier,
  size: Dp = 36.dp,
) {
  val url = photos.firstOrNull()?.thumbUrl() ?: externalImage?.thumbnailUrl

  if (url != null) {
    AsyncImage(
      model = url,
      contentDescription = name,
      contentScale = ContentScale.Crop,
      modifier = modifier.size(size).clip(RoundedCornerShape(4.dp)),
    )
  } else {
    Box(
      modifier = modifier
        .size(size)
        .clip(RoundedCornerShape(4.dp))
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
