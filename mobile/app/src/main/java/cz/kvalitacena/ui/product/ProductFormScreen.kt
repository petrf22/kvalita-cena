package cz.kvalitacena.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.PhotoSlot
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.categoryChoicesFor
import cz.kvalitacena.ui.common.openUrl
import cz.kvalitacena.ui.common.rememberMoneyFormatter

private val UNIT_BASE_LABEL_RES = mapOf(
  "COUNT" to R.string.unit_base_count,
  "MASS" to R.string.unit_base_mass,
  "VOLUME" to R.string.unit_base_volume,
)

/**
 * Založení zboží — nejdřív nabídne podobné existující položky (i bezkódové druhové, viz
 * ProductFormViewModel), teprve když se mezi nimi nic nehodí, jde založit nové. `barcode` je
 * naskenovaný kód, který se v katalogu nenašel (viz PriceEntryScreen), předvyplní pole kódu;
 * `null` zakládá bezkódovou druhovou položku (ze záložky Hledat, viz SearchScreen).
 *
 * `onCreated` (výchozí = zavolá `onDone`) odlišuje úspěch od zrušení — SearchScreen ho
 * přepisuje, ať po založení naskočí rovnou na zápis ceny nového zboží, místo aby se appka jen
 * vrátila zpět (`onDone` zůstává čisté "zrušit"/zavřít, stejné jako dřív).
 */
@Composable
fun ProductFormScreen(
  barcode: String?,
  onDone: () -> Unit,
  onCreated: (productId: String) -> Unit = { onDone() },
) {
  val viewModel: ProductFormViewModel = viewModel(
    factory = viewModelFactory { initializer { ProductFormViewModel(AppContainer.graphQlClient, barcode) } },
  )

  LaunchedEffect(viewModel.created) {
    viewModel.created?.let {
      NavigationResults.newProduct = it
      onCreated(it.id)
    }
  }

  val context = LocalContext.current

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.product_form_title), style = MaterialTheme.typography.headlineSmall)
    Gap()

    // ODbL vyžaduje, aby appka u převzatých dat vždy uvedla zdroj (docs/datovy-model.md) —
    // celá karta je klikací na candidate.sourceUrl, stejný vzor jako ExternalLinkRow v detailu.
    viewModel.offCandidate?.let { candidate ->
      OutlinedButton(
        onClick = { openUrl(context, candidate.sourceUrl) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          candidate.image?.let { image ->
            AsyncImage(
              model = image.thumbnailUrl,
              contentDescription = candidate.name,
              modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
              contentScale = ContentScale.Crop,
            )
          }
          Column {
            Text(stringResource(R.string.product_form_off_banner_title), style = MaterialTheme.typography.bodyMedium)
            Text(candidate.attribution, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      Gap()
    }

    val genericTag = stringResource(R.string.product_form_generic_tag)
    SearchableDropdown(
      query = viewModel.name,
      onQueryChange = viewModel::onNameChange,
      suggestions = viewModel.suggestions,
      onSelect = { viewModel.useExisting(it) },
      itemLabel = { summary ->
        val kind = if (summary.isGeneric) " ($genericTag)" else ""
        val brand = summary.brand?.name?.let { "$it · " } ?: ""
        "${summary.name}$kind — $brand${summary.category.name}"
      },
      label = stringResource(R.string.product_form_name_label),
      loading = viewModel.suggestionsLoading,
      modifier = Modifier.fillMaxWidth(),
    )
    if (viewModel.suggestions.isNotEmpty()) {
      Text(
        stringResource(R.string.product_form_suggestions_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Gap()

    // LocalConfiguration.current.locales[0], ne Locale.getDefault() — appka může jazyk
    // přepnout za běhu (docs/lokalizace.md), stejný důvod jako RelativeDate.kt/Money.kt.
    val locale = LocalConfiguration.current.locales[0]
    val categoryChoices = remember(viewModel.categories, viewModel.categoryQuery, locale) {
      categoryChoicesFor(viewModel.categoryQuery, viewModel.categories, locale)
    }
    SearchableDropdown(
      query = viewModel.categoryQuery,
      onQueryChange = viewModel::onCategoryQueryChange,
      suggestions = categoryChoices,
      onSelect = { viewModel.onCategorySelected(it.category) },
      itemLabel = { it.label },
      label = stringResource(R.string.product_form_category_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    SingleLineTextField(
      value = viewModel.brandName,
      onValueChange = { viewModel.brandName = it },
      label = stringResource(R.string.product_form_brand_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    Text(stringResource(R.string.product_form_unit_base_label), style = MaterialTheme.typography.titleMedium)
    Row {
      UNIT_BASE_LABEL_RES.forEach { (value, labelRes) ->
        FilterChip(
          selected = viewModel.unitBase == value,
          onClick = { viewModel.unitBase = value },
          label = { Text(stringResource(labelRes)) },
          modifier = Modifier.padding(end = 8.dp),
        )
      }
    }
    Gap()

    if (viewModel.unitBase != "COUNT") {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          stringResource(R.string.product_form_variable_weight_label),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        Switch(checked = viewModel.isVariableWeight, onCheckedChange = { viewModel.isVariableWeight = it })
      }
      Gap()

      if (!viewModel.isVariableWeight) {
        SingleLineTextField(
          value = viewModel.netContentValue,
          onValueChange = { input -> if (input.matches(Regex("^\\d*[.,]?\\d*$"))) viewModel.netContentValue = input },
          label = stringResource(
            if (viewModel.unitBase == "MASS") R.string.product_form_mass_label else R.string.product_form_volume_label,
          ),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
        Gap()
      }
    }

    SingleLineTextField(
      value = viewModel.piecesInPack,
      onValueChange = { input -> if (input.all(Char::isDigit)) viewModel.piecesInPack = input },
      label = stringResource(R.string.product_form_pieces_in_pack_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    HorizontalDivider()
    Gap()

    SingleLineTextField(
      value = viewModel.code,
      onValueChange = { viewModel.code = it },
      label = stringResource(R.string.product_form_code_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      stringResource(R.string.product_form_code_hint, rememberMoneyFormatter("CZK").format(45)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()

    PhotoSlot(
      label = stringResource(R.string.product_form_item_photo_label),
      onUriChange = { viewModel.itemPhotoUri = it },
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    PhotoSlot(
      label = stringResource(R.string.product_form_label_photo_label),
      onUriChange = { viewModel.labelPhotoUri = it },
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    viewModel.saveError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Gap()
    }
    if (viewModel.saving && (viewModel.itemPhotoUri != null || viewModel.labelPhotoUri != null)) {
      Text(
        stringResource(R.string.product_form_uploading_photos),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Gap()
    }
    if (viewModel.photoUploadFailed) {
      Text(
        stringResource(R.string.product_form_photo_upload_failed_warning),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
      Gap()
    }

    Button(
      onClick = { viewModel.submit(context) },
      enabled = viewModel.name.isNotBlank() && viewModel.selectedCategoryId != null && !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (viewModel.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text(stringResource(R.string.product_form_submit))
    }
    Gap()
    OutlinedButton(onClick = onDone, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.product_form_back_without_creating))
    }
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}
