package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.ui.theme.Spacing

/**
 * „Zboží z účtenky" (`Product.createdVia = RECEIPT`, docs/reputace.md) — druhová položka, kterou
 * založilo zveřejnění nespárovaného řádku účtenky. Spolehlivé jsou na ní jen název z tisku,
 * cena, obchod a čas nákupu; značku, gramáž ani kategorii nikdo neověřil. Klient ji proto
 * všude kreslí se štítkem a na detailu nabídne dopárování.
 *
 * Porovnává se přesně s `RECEIPT` — neznámá budoucí hodnota enumu se bere jako běžné zboží
 * (appka typy ze schématu negeneruje, kořenový CLAUDE.md).
 */
internal const val CREATED_VIA_RECEIPT = "RECEIPT"

val Product.isReceiptItem: Boolean get() = createdVia == CREATED_VIA_RECEIPT

val ProductSummary.isReceiptItem: Boolean get() = createdVia == CREATED_VIA_RECEIPT

/**
 * Štítek „Z účtenky" do řádku seznamu (výsledky hledání, řádek účtenky, našeptávač) — stejný
 * tvar jako čip stavu řádku účtenky. Tón `tertiaryContainer` = „pozor, neověřené", stejně jako
 * nespárovaný řádek; barvy z tématu fungují ve světlém i tmavém režimu.
 */
@Composable
fun ReceiptItemBadge(modifier: Modifier = Modifier) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    shape = MaterialTheme.shapes.small,
    modifier = modifier,
  ) {
    Text(
      stringResource(R.string.receipt_item_badge),
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
  }
}

/**
 * Štítek „Z účtenky" mezi čipy detailu zboží — `AssistChip` jako sousední „Čeká na potvrzení",
 * ale s ikonou účtenky a tónem [ReceiptItemBadge], ať se od běžných štítků odliší na první pohled.
 */
@Composable
fun ReceiptItemChip(modifier: Modifier = Modifier) {
  AssistChip(
    onClick = {},
    label = { Text(stringResource(R.string.receipt_item_badge)) },
    leadingIcon = {
      Icon(
        painterResource(R.drawable.ic_receipt),
        contentDescription = null,
        modifier = Modifier.size(AssistChipDefaults.IconSize),
      )
    },
    colors = AssistChipDefaults.assistChipColors(
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
      leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ),
    border = null,
    modifier = modifier,
  )
}
