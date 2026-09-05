package cz.kvalitacena.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import cz.kvalitacena.network.ProductSummary
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.PhotoSlot
import cz.kvalitacena.ui.common.SearchableDropdown
import cz.kvalitacena.ui.common.SingleLineTextField
import cz.kvalitacena.ui.common.StorePicker
import cz.kvalitacena.ui.common.categoryChoicesFor
import cz.kvalitacena.ui.common.openUrl
import cz.kvalitacena.ui.common.rememberMoneyFormatter
import cz.kvalitacena.ui.navigation.LocalNavigationExitGuard
import cz.kvalitacena.ui.navigation.ReportUnsavedChanges

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
 *
 * Se zadaným [productId] přejde do režimu editace existujícího zboží — používá ji
 * ProductDetailScreen (`onDone` po uložení). V editaci appka skryje návrhy podobných položek
 * a fotoslots (fotky se spravují v galerii na detailu) a čárový kód je jen ke čtení.
 */
@Composable
fun ProductFormScreen(
  barcode: String?,
  productId: String? = null,
  onDone: () -> Unit,
  onAddStore: () -> Unit = {},
  onCreated: (productId: String) -> Unit = { onDone() },
) {
  val viewModel: ProductFormViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        ProductFormViewModel(
          AppContainer.graphQlClient,
          barcode,
          AppContainer.countryStore,
          AppContainer.lastStoreStore,
          productId,
        )
      }
    },
  )
  var formDirty by rememberSaveable { mutableStateOf(false) }
  val exitGuard = LocalNavigationExitGuard.current
  ReportUnsavedChanges(formDirty && viewModel.created == null)

  LaunchedEffect(viewModel.created) {
    viewModel.created?.let {
      formDirty = false
      if (viewModel.isEditing) {
        NavigationResults.updatedProduct = it
        onDone()
      } else {
        NavigationResults.newProduct = it
        NavigationResults.productAlias = if (viewModel.usingExisting) viewModel.matchedAlias else null
        onCreated(it.id)
      }
    }
  }

  val context = LocalContext.current
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()

  LaunchedEffect(Unit) {
    NavigationResults.newStore?.let {
      viewModel.onNewStoreCreated(it)
      NavigationResults.newStore = null
    }
  }

  if (viewModel.loadingExisting) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(
      stringResource(if (viewModel.isEditing) R.string.product_form_edit_title else R.string.product_form_title),
      style = MaterialTheme.typography.headlineSmall,
    )
    Gap()

    if (!viewModel.isEditing && viewModel.code.isBlank()) {
      Text(
        stringResource(R.string.product_form_store_first_hint),
        style = MaterialTheme.typography.bodyMedium,
      )
      StorePicker(
        query = viewModel.storeQuery,
        onQueryChange = { formDirty = true; viewModel.onStoreQueryChange(it) },
        suggestions = viewModel.storeSuggestions,
        searching = viewModel.storeSearching,
        selectedStoreId = viewModel.selectedStore?.id,
        onSelect = { formDirty = true; viewModel.onStoreSelected(it) },
        onAddNew = onAddStore,
        isLoggedIn = accessToken != null,
        homeCountry = AppContainer.countryStore.country,
        modifier = Modifier.fillMaxWidth(),
      )
      Gap()
    }

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
    val unconfirmedTag = stringResource(R.string.product_form_unconfirmed_tag)
    val chainScope = stringResource(R.string.product_form_chain_scope)
    val storeScope = stringResource(R.string.product_form_store_scope)
    val summaryLabel: (ProductSummary) -> String = { summary ->
      val kind = if (summary.isGeneric) " ($genericTag)" else ""
      val unconfirmed = if (summary.status == "DRAFT") " ($unconfirmedTag)" else ""
      val brand = summary.brand?.name?.let { "$it · " } ?: ""
      val scope = when (summary.catalogScope) {
        "CHAIN" -> summary.scopeChain?.name?.let { " · $chainScope: $it" }.orEmpty()
        "STORE" -> summary.scopeStore?.name?.let { " · $storeScope: $it" }.orEmpty()
        else -> ""
      }
      "${summary.name}$kind$unconfirmed — $brand${summary.category.name}$scope"
    }

    // Nabídka obchodu se ukáže JEŠTĚ NEŽ uživatel začne psát — u bezkódového zboží vznikají
    // duplicity hlavně tím, že člověk nevidí, co v obchodě už je, a název si vymyslí
    // (docs/reputace.md, "Zboží bez čárového kódu"). Dropdown níž se otevře až při psaní,
    // proto samostatný seznam, ne jen jeho naplnění.
    if (viewModel.browsingStoreOffer && viewModel.suggestions.isNotEmpty()) {
      Text(
        stringResource(R.string.product_form_store_offer_title),
        style = MaterialTheme.typography.titleSmall,
      )
      viewModel.suggestions.forEach { summary ->
        OutlinedButton(
          onClick = { viewModel.useExisting(summary) },
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
          Text(summaryLabel(summary), style = MaterialTheme.typography.bodyMedium)
        }
      }
      Gap()
    }

    SearchableDropdown(
      query = viewModel.name,
      onQueryChange = { formDirty = true; viewModel.onNameChange(it) },
      suggestions = viewModel.suggestions,
      onSelect = { viewModel.useExisting(it) },
      itemLabel = summaryLabel,
      // Popisek nese jazyk: pole "Název" je VŽDY v jazyce appky (docs/lokalizace.md), takže
      // do něj nikdy nespadne cizojazyčný název z OFF — ten se ukáže v upozornění a v sekci
      // ostatních jazyků níž.
      label = stringResource(
        R.string.product_form_name_label_with_lang,
        stringResource(R.string.product_form_name_label),
        langName(viewModel.nameLang),
      ),
      loading = viewModel.suggestionsLoading,
      modifier = Modifier.fillMaxWidth(),
    )
    if (viewModel.suggestions.isNotEmpty() && !viewModel.browsingStoreOffer) {
      Text(
        stringResource(R.string.product_form_suggestions_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    viewModel.foreignNameHint?.let { (lang, foreignName) ->
      Text(
        stringResource(R.string.product_form_foreign_name_warning, langName(lang), foreignName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    Gap()

    TextButton(onClick = { viewModel.toggleOtherNames() }) {
      Text(
        stringResource(
          if (viewModel.otherNamesExpanded) R.string.product_form_other_names_hide
          else R.string.product_form_other_names_show,
        ),
      )
    }
    if (viewModel.otherNamesExpanded) {
      Text(
        stringResource(R.string.product_form_other_names_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      viewModel.otherLangs.forEach { lang ->
        OutlinedTextField(
          value = viewModel.otherNames[lang].orEmpty(),
          onValueChange = { formDirty = true; viewModel.onOtherNameChange(lang, it) },
          label = { Text(langName(lang)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
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
      onQueryChange = { formDirty = true; viewModel.onCategoryQueryChange(it) },
      suggestions = categoryChoices,
      onSelect = { formDirty = true; viewModel.onCategorySelected(it.category) },
      itemLabel = { it.label },
      label = stringResource(R.string.product_form_category_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    SingleLineTextField(
      value = viewModel.brandName,
      onValueChange = { formDirty = true; viewModel.brandName = it },
      label = stringResource(R.string.product_form_brand_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    Text(stringResource(R.string.product_form_unit_base_label), style = MaterialTheme.typography.titleMedium)
    Row {
      UNIT_BASE_LABEL_RES.forEach { (value, labelRes) ->
        FilterChip(
          selected = viewModel.unitBase == value,
          onClick = { formDirty = true; viewModel.unitBase = value },
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
        Switch(checked = viewModel.isVariableWeight, onCheckedChange = { formDirty = true; viewModel.isVariableWeight = it })
      }
      Gap()

      if (!viewModel.isVariableWeight) {
        SingleLineTextField(
          value = viewModel.netContentValue,
          onValueChange = { input -> if (input.matches(Regex("^\\d*[.,]?\\d*$"))) { formDirty = true; viewModel.netContentValue = input } },
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
      onValueChange = { input -> if (input.all(Char::isDigit)) { formDirty = true; viewModel.piecesInPack = input } },
      label = stringResource(R.string.product_form_pieces_in_pack_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()

    HorizontalDivider()
    Gap()

    SingleLineTextField(
      value = viewModel.code,
      onValueChange = { formDirty = true; viewModel.code = it },
      label = stringResource(R.string.product_form_code_label),
      readOnly = viewModel.isEditing,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      if (viewModel.isEditing) {
        stringResource(R.string.product_form_code_read_only_hint)
      } else {
        stringResource(R.string.product_form_code_hint, rememberMoneyFormatter("CZK").format(45))
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()

    if (!viewModel.isEditing) {
      // Obě fotky se ukládají s jazykem obalu (= jazyk appky). U etikety je to podstata věci —
      // je to fotka TEXTU složení (docs/lokalizace.md, docs/ai.md).
      PhotoSlot(
        label = stringResource(
          R.string.product_form_item_photo_label_with_lang,
          stringResource(R.string.product_form_item_photo_label),
          langName(viewModel.nameLang),
        ),
        onUriChange = { formDirty = true; viewModel.itemPhotoUri = it },
        modifier = Modifier.fillMaxWidth(),
      )
      Gap()
      PhotoSlot(
        label = stringResource(
          R.string.product_form_label_photo_label_with_lang,
          stringResource(R.string.product_form_label_photo_label),
          langName(viewModel.nameLang),
        ),
        onUriChange = { formDirty = true; viewModel.labelPhotoUri = it },
        modifier = Modifier.fillMaxWidth(),
      )
      Gap()
    }

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
      enabled = viewModel.canSubmit,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (viewModel.saving) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
      } else {
        Text(stringResource(if (viewModel.isEditing) R.string.product_form_submit_edit else R.string.product_form_submit))
      }
    }
    Gap()
    OutlinedButton(
      onClick = { exitGuard.requestNavigation(onDone) },
      enabled = !viewModel.saving,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        stringResource(
          if (viewModel.isEditing) R.string.common_cancel else R.string.product_form_back_without_creating,
        ),
      )
    }
  }
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
}

/**
 * Jméno jazyka pro popisky a upozornění ("česky", "německy") — přes `values/` resources, takže
 * se překládá stejně jako zbytek appky (docs/lokalizace.md). Neznámý kód se ukáže tak, jak
 * přišel; nastat může jen u dat ze serveru, ne u jazyků appky.
 */
@Composable
private fun langName(lang: String): String = when (lang) {
  "cs" -> stringResource(R.string.lang_cs)
  "sk" -> stringResource(R.string.lang_sk)
  "en" -> stringResource(R.string.lang_en)
  "pl" -> stringResource(R.string.lang_pl)
  "de" -> stringResource(R.string.lang_de)
  else -> lang
}
