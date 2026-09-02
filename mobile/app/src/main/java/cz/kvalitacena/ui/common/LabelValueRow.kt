package cz.kvalitacena.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Řádek „popisek vlevo, hodnota vpravo" — cenové řádky (detail zboží, moje příspěvky,
 * hledání). Popisek má `weight(1f)`, takže hodnota se měří první a nikdy se neláme: bez
 * `weight` měří `Row` děti postupně zleva, dlouhý název obchodu si vezme celou šířku a na
 * cenu vpravo nezbyde nic — láme se pak po jednom znaku (viz oprava, kdy „Pekárna Kabát
 * (Arkády Pankrác) — Běžná cena" udělalo z „109,00 Kč" svislý sloupec znaků).
 *
 * Jediný layoutový primitiv v `ui/common/` — zbytek souborů tady jsou čisté převody dat na
 * string (`Money.kt`, `PriceKindLabels.kt`, `StoreLabel.kt`). Je tu záměrně, protože stejný
 * tvar řádku je duplikovaný na víc obrazovkách a bez sdíleného místa se tahle vada vrátí.
 */
@Composable
fun LabelValueRow(
  modifier: Modifier = Modifier,
  verticalAlignment: Alignment.Vertical = Alignment.Top,
  label: @Composable ColumnScope.() -> Unit,
  value: @Composable () -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = verticalAlignment,
  ) {
    Column(modifier = Modifier.weight(1f), content = label)
    value()
  }
}

/** Zkratka pro nejčastější případ: jeden řádek textu vlevo, částka vpravo. */
@Composable
fun LabelValueRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
  LabelValueRow(
    modifier = modifier,
    label = { Text(label, style = style) },
    value = { Text(value, style = style, softWrap = false, textAlign = TextAlign.End) },
  )
}
