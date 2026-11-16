package cz.kvalitacena.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R

/**
 * Popisky základu ceny (`QuantityBasis`: za balení / kilogram / litr / kus) — jeden zdroj pro
 * zápis ceny i pro frontu cen zapsaných bez signálu (`ui/pending/`), kde se u zboží na váhu
 * ptá dodatečně. Neznámá hodnota se ukáže syrová, stejný důvod jako u `priceKindLabel`:
 * server může přidat hodnotu dřív, než vyjde nová appka.
 */
@StringRes
private fun quantityBasisLabelRes(basis: String): Int? = when (basis) {
  "PACKAGE" -> R.string.quantity_basis_package
  "PER_KG" -> R.string.quantity_basis_per_kg
  "PER_L" -> R.string.quantity_basis_per_l
  "PER_PIECE" -> R.string.quantity_basis_per_piece
  else -> null
}

@Composable
fun quantityBasisLabel(basis: String): String = quantityBasisLabelRes(basis)?.let { stringResource(it) } ?: basis

/**
 * Nabídka pro zboží na váhu (`isVariableWeight`) — bez PACKAGE, ten je výchozí jen pro ostatní
 * zboží a u váhového nedává smysl (cena na cedulce je vždy za kg/l/kus). Web: shared/enum-labels.ts.
 */
val SELECTABLE_VARIABLE_WEIGHT_QUANTITY_BASES = listOf("PER_KG", "PER_L", "PER_PIECE")
