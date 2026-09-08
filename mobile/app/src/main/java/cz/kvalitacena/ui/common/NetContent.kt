package cz.kvalitacena.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R
import java.text.NumberFormat

/** Zkratky jednotek gramáže/objemu — protějšek `enum.netContentUom.*` na webu. */
private val NET_CONTENT_UOM_LABEL_RES = mapOf(
  "G" to R.string.net_content_uom_g,
  "KG" to R.string.net_content_uom_kg,
  "ML" to R.string.net_content_uom_ml,
  "L" to R.string.net_content_uom_l,
  "PCS" to R.string.net_content_uom_pcs,
)

/** Popisek jednotky pro combobox formuláře; `null` je volba „nevyplněno". */
@Composable
fun netContentUomLabel(uom: String?): String = when (uom) {
  null -> stringResource(R.string.product_form_net_content_uom_none)
  else -> NET_CONTENT_UOM_LABEL_RES[uom]?.let { stringResource(it) } ?: uom
}

/**
 * Gramáž/objem do jednoho krátkého štítku („250 g", „0,5 l") pro seznamy a podtitulky —
 * `null`, když zboží gramáž nemá a cena tedy platí za balení. Webový protějšek je řádek
 * `product-meta` v `features/product-detail/product-detail-page.html`.
 *
 * Váhové zboží se rozhoduje PRVNÍ a hodnotu ignoruje: server ji u něj nepoužívá vůbec
 * (`NetContentCalculator` vrací 1 ještě před kontrolou jednotky, kořenový CLAUDE.md), za kg/l
 * se cena označuje až u zápisu ceny — číslo z katalogu by tu tvrdilo něco, co neplatí.
 * `PCS` a rozpojená dvojice hodnota/jednotka nedávají štítek stejně jako ve formuláři
 * (`toFormNetContent` v ui/product/ProductFormValidation.kt).
 */
@Composable
fun netContentLabel(value: Double?, uom: String?, isVariableWeight: Boolean): String? =
  rememberNetContentLabeler()(value, uom, isVariableWeight)

/**
 * Táž logika jako [netContentLabel], ale jako obyčejná funkce s předem načtenými řetězci — pro
 * místa, kde se štítek skládá mimo `@Composable` kontext (`summaryLabel` v ProductFormScreen.kt).
 *
 * Číslo se formátuje podle jazyka appky (desetinná čárka/tečka) bez doplňování nul, takže
 * „250 g" zůstane „250 g" a jen skutečně desetinná hodnota se ukáže s místy („0,5 l"). Tři
 * desetinná místa odpovídají `NUMERIC(12,3)` sloupce `core.product.net_content_value`. Locale
 * se bere z konfigurace, ne natvrdo — stejný důvod jako u [rememberMoneyFormatter] (Money.kt).
 */
@Composable
fun rememberNetContentLabeler(): (Double?, String?, Boolean) -> String? {
  val locale = LocalConfiguration.current.locales[0]
  val variableWeight = stringResource(R.string.net_content_variable_weight)
  val uomLabels = mapOf(
    "G" to stringResource(R.string.net_content_uom_g),
    "KG" to stringResource(R.string.net_content_uom_kg),
    "ML" to stringResource(R.string.net_content_uom_ml),
    "L" to stringResource(R.string.net_content_uom_l),
  )
  return remember(locale, variableWeight, uomLabels) {
    val formatter = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }
    val labeler: (Double?, String?, Boolean) -> String? = { value, uom, isVariableWeight ->
      when {
        isVariableWeight -> variableWeight
        value == null || uom == null || uom == "PCS" -> null
        else -> "${formatter.format(value)} ${uomLabels[uom] ?: uom}"
      }
    }
    labeler
  }
}
