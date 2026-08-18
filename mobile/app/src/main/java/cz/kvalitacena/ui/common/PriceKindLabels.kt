package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R

/**
 * Jediný zdroj popisků druhu ceny — dřív byla tahle mapa duplicitně ve dvou souborech
 * (PriceEntryScreen, ProductDetailScreen) a lišily se (jedna neznala MULTIBUY). `else ->` u
 * neznámé hodnoty (ne shoda podle enumu) schválně — appka netypuje `PriceKind` z GraphQL
 * schématu jako web, backend tak může přidat hodnotu dřív, než se vydá nová appka
 * (docs/lokalizace.md).
 */
@StringRes
private fun priceKindLabelRes(kind: String): Int = when (kind) {
  "REGULAR" -> R.string.price_kind_regular
  "PROMO" -> R.string.price_kind_promo
  "CLUB_CARD" -> R.string.price_kind_club_card
  "CLEARANCE" -> R.string.price_kind_clearance
  "MULTIBUY" -> R.string.price_kind_multibuy
  else -> R.string.price_kind_unknown
}

@Composable
fun priceKindLabel(kind: String): String = stringResource(priceKindLabelRes(kind))

/**
 * Druhy ceny nabízené ve formuláři zápisu — stejný seznam jako web `SELECTABLE_PRICE_KINDS`
 * (`shared/enum-labels.ts`), včetně MULTIBUY (množstevní sleva, `ui/price/PriceEntryScreen.kt`
 * umí i její pole navíc `multibuyQty`/`multibuyTotal`, docs/datovy-model.md).
 * `availablePriceKinds` (`ui/price/PriceRowValidation.kt`) z týhle množiny navíc vyloučí druhy
 * použité v jiných řádcích jedné dávky.
 */
val SELECTABLE_PRICE_KINDS = listOf("REGULAR", "PROMO", "CLUB_CARD", "CLEARANCE", "MULTIBUY")
