package cz.kvalitacena.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.kvalitacena.R
import cz.kvalitacena.network.Store
import cz.kvalitacena.ui.theme.Spacing

// Nad tenhle počet se seznam v rolovacím formuláři rozteče — zbytek čeká za "Zobrazit další".
private const val VISIBLE_NEARBY_COUNT = 5

// Dotykový cíl podle docs/design.md ("Minimální dotykový cíl je 44 px").
private val ROW_MIN_HEIGHT = 44.dp

/**
 * Výběr obchodu při zápisu ceny. Od 2026-09 je to **viditelný seznam**, ne textové pole
 * s tlačítkem a skrytou nabídkou: obchody v okolí se hledají samy hned po otevření
 * (PriceEntryViewModel.shouldAutoLocate), takže není co mačkat a není důvod nález schovávat.
 * Dřívější tlačítko "Najít v okolí" zmizelo — hledat kolem sebe je při stání v obchodě
 * samozřejmost, ne volba.
 *
 * Tři cesty k obchodu zůstávají (docs/datovy-model.md, "Identita provozovny") a všechny jsou
 * vidět naráz: seznam v okolí, psaní názvu/města do pole nahoře a založení nového obchodu.
 * Poslední z nich je nově plnohodnotné tlačítko pod seznamem, ne poslední položka rozbalovací
 * nabídky, kterou uživatel musel objevit — právě ta byla slepá ulička.
 *
 * Prázdný stav neříká jen "nic jsme nenašli": vysvětlí proč a nabídne pokračování včetně
 * nabídky z OpenStreetMap ([osmState]), viz docs/design.md, "Čtyři stavy každé obrazovky".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePicker(
  query: String,
  onQueryChange: (String) -> Unit,
  suggestions: List<Store>,
  searching: Boolean,
  selectedStoreId: String?,
  onSelect: (Store) -> Unit,
  onAddNew: () -> Unit,
  // Hledání v okolí je celé volitelné — ProductFormScreen tenhle picker používá jen jako
  // našeptávač "ke které provozovně lokální zboží patří", tam poloha nehraje roli.
  nearbyState: NearbyState = NearbyState.Idle,
  osmState: OsmState = OsmState.Idle,
  onRequestLocationPermission: () -> Unit = {},
  onOpenLocationSettings: () -> Unit = {},
  onRetryNearby: () -> Unit = {},
  onWiderRadius: (() -> Unit)? = null,
  widerRadiusLabel: String? = null,
  onSearchOsm: () -> Unit = {},
  onSelectOsmCandidate: (NearbyOsmStore) -> Unit = {},
  /** Systém dává jen zaokrouhlenou polohu — v malém okruhu pak nemá co najít, viz [EmptyNearby]. */
  approximateLocation: Boolean = false,
  isLoggedIn: Boolean,
  homeCountry: String?,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    SearchableDropdown(
      query = query,
      onQueryChange = onQueryChange,
      suggestions = suggestions,
      onSelect = onSelect,
      itemLabel = { store -> storeLabel(store, homeCountry) },
      label = stringResource(R.string.store_picker_label),
      loading = searching,
      modifier = Modifier.fillMaxWidth(),
    )

    when (nearbyState) {
      is NearbyState.Idle -> Unit

      is NearbyState.Locating -> StatusLine(stringResource(R.string.store_picker_searching_nearby), loading = true)

      is NearbyState.PermissionMissing -> ExplainedAction(
        text = stringResource(R.string.store_picker_permission_needed),
        actionLabel = stringResource(R.string.store_picker_permission_grant),
        onAction = onRequestLocationPermission,
      )

      is NearbyState.LocationDisabled -> ExplainedAction(
        text = stringResource(R.string.store_picker_location_disabled),
        actionLabel = stringResource(R.string.store_picker_open_location_settings),
        onAction = onOpenLocationSettings,
      )

      is NearbyState.LocationTimeout -> ExplainedAction(
        text = stringResource(R.string.store_picker_location_timeout),
        actionLabel = stringResource(R.string.store_picker_retry),
        onAction = onRetryNearby,
      )

      // Vlastní text místo obecné chyby ze sítě: reálný důvod je tady skoro vždy chybějící
      // signál v obchodě, a uživateli je užitečnější vědět, co mu zbývá, než že "se něco
      // pokazilo". Zápis ceny sítí projít musí, takže se neslibuje, že půjde všechno.
      is NearbyState.Failed -> ExplainedAction(
        text = stringResource(R.string.store_picker_nearby_failed),
        actionLabel = stringResource(R.string.store_picker_retry),
        onAction = onRetryNearby,
      )

      is NearbyState.Loaded -> if (nearbyState.stores.isEmpty()) {
        EmptyNearby(approximateLocation = approximateLocation)
      } else {
        NearbyList(
          stores = nearbyState.stores,
          selectedStoreId = selectedStoreId,
          onSelect = onSelect,
          homeCountry = homeCountry,
        )
      }
    }

    // Nabídka z OpenStreetMap se ukáže jen tam, kde má smysl — poloha je známá a vlastní
    // katalog v okolí nestačil. Bez přihlášení se nenačítá vůbec: kandidát je bez createStore
    // (které přihlášení vyžaduje, docs/reputace.md T1) k ničemu.
    if (isLoggedIn && nearbyState is NearbyState.Loaded) {
      OsmSection(
        state = osmState,
        empty = nearbyState.stores.isEmpty(),
        onSearchOsm = onSearchOsm,
        onSelectCandidate = onSelectOsmCandidate,
      )
    }

    // Akce jsou vidět VŽDYCKY, ne až v prázdném stavu — "+ Přidat nový obchod" jako poslední
    // položka rozbalovací nabídky byla přesně ta slepá ulička, kvůli které se tenhle výběr
    // předělával.
    Actions(
      // Širší okolí má smysl nabídnout jen tam, kde se vůbec hledalo — bez polohy by to bylo
      // tlačítko, po kterém se nic nestane.
      onWiderRadius = onWiderRadius.takeIf { nearbyState is NearbyState.Loaded },
      widerRadiusLabel = widerRadiusLabel,
      onAddNew = onAddNew,
      isLoggedIn = isLoggedIn,
    )

    // Mapa se sama schová, když v suggestions není obchod se souřadnicemi (StoreMap.stores.isEmpty()),
    // a dlaždice se stahují až po klepnutí na "Zobrazit obchody na mapě" (docs/soukromi.md).
    if (suggestions.isNotEmpty()) {
      StoreMap(
        stores = suggestions,
        selectedStoreId = selectedStoreId,
        onSelect = onSelect,
        homeCountry = homeCountry,
        modifier = Modifier.padding(top = Spacing.sm),
      )
    }
  }
}

@Composable
private fun NearbyList(
  stores: List<NearbyStore>,
  selectedStoreId: String?,
  onSelect: (Store) -> Unit,
  homeCountry: String?,
) {
  var showAll by remember(stores) { mutableStateOf(false) }
  val visible = if (showAll) stores else stores.take(VISIBLE_NEARBY_COUNT)

  Column(modifier = Modifier.padding(top = Spacing.sm)) {
    Text(
      stringResource(R.string.store_picker_nearby_title),
      style = MaterialTheme.typography.titleSmall,
    )
    visible.forEach { nearby ->
      NearbyRow(
        label = storeLabel(nearby.store, homeCountry),
        distance = distanceLabel(nearby.distanceMeters).asString(),
        // Provozovna od nedůvěryhodného autora čeká na potvrzení dalších přispěvatelů
        // (docs/reputace.md) a vidí ji jen autor — bez štítku by ji člověk považoval za
        // běžný obchod a příště ji založil znovu.
        note = if (nearby.store.pendingConfirmation) {
          stringResource(R.string.store_picker_pending_confirmation)
        } else {
          null
        },
        selected = nearby.store.id == selectedStoreId,
        onClick = { onSelect(nearby.store) },
      )
    }
    if (!showAll && stores.size > VISIBLE_NEARBY_COUNT) {
      TextButton(onClick = { showAll = true }) {
        Text(stringResource(R.string.store_picker_show_more, stores.size - VISIBLE_NEARBY_COUNT))
      }
    }
  }
}

@Composable
private fun NearbyRow(
  label: String,
  distance: String,
  note: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = ROW_MIN_HEIGHT)
      .clickable(onClick = onClick)
      .padding(vertical = Spacing.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Text(
      distance,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = Spacing.sm),
    )
  }
}

@Composable
private fun EmptyNearby(approximateLocation: Boolean) {
  Column(modifier = Modifier.padding(top = Spacing.sm)) {
    Text(
      stringResource(R.string.store_picker_none_nearby_title),
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      stringResource(R.string.store_picker_none_nearby_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Jinak by to vypadalo, že appka neumí hledat — přitom jí systém dal polohu o kilometry
    // vedle. Doporučit širší okruh je tady užitečnější rada než "zkus to znovu".
    if (approximateLocation) {
      Text(
        stringResource(R.string.store_picker_approximate_location),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
      )
    }
  }
}

@Composable
private fun OsmSection(
  state: OsmState,
  empty: Boolean,
  onSearchOsm: () -> Unit,
  onSelectCandidate: (NearbyOsmStore) -> Unit,
) {
  Column(modifier = Modifier.padding(top = Spacing.sm)) {
    when (state) {
      is OsmState.Idle -> if (!empty) {
        // Vlastní katalog něco našel — nabídka z OSM je pak jen doplněk na vyžádání, ať se
        // cizí služba nedotazuje při každém skenu.
        TextButton(onClick = onSearchOsm) { Text(stringResource(R.string.store_picker_osm_search)) }
      }

      is OsmState.Loading -> StatusLine(stringResource(R.string.store_picker_searching_nearby), loading = true)

      is OsmState.Unavailable -> Text(
        stringResource(R.string.store_picker_osm_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      is OsmState.Failed -> Text(
        state.message.asString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )

      is OsmState.Loaded -> if (state.candidates.isEmpty()) {
        Text(
          stringResource(R.string.store_picker_osm_none),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        HorizontalDivider()
        Text(
          stringResource(R.string.store_picker_osm_title),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = Spacing.sm),
        )
        // Stejné vrátkování jako u vlastních obchodů — v hustém centru jich Overpass vrátí
        // klidně čtyřicet a formulář by se v rolování ztratil.
        var showAllOsm by remember(state.candidates) { mutableStateOf(false) }
        val visibleOsm = if (showAllOsm) state.candidates else state.candidates.take(VISIBLE_NEARBY_COUNT)
        visibleOsm.forEach { candidate ->
          NearbyRow(
            label = osmCandidateLabel(candidate),
            distance = distanceLabel(candidate.distanceMeters).asString(),
            note = null,
            selected = false,
            onClick = { onSelectCandidate(candidate) },
          )
        }
        if (!showAllOsm && state.candidates.size > VISIBLE_NEARBY_COUNT) {
          TextButton(onClick = { showAllOsm = true }) {
            Text(stringResource(R.string.store_picker_show_more, state.candidates.size - VISIBLE_NEARBY_COUNT))
          }
        }
        // Atribuce je podle ODbL povinná a text chodí ze serveru (je lokalizovaný,
        // docs/lokalizace.md) — nikdy se nezadrátovává do appky.
        Text(
          state.attribution,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Název a adresa kandidáta ve stejném duchu jako [storeLabel] u vlastních obchodů. */
private fun osmCandidateLabel(candidate: NearbyOsmStore): String {
  val place = candidate.candidate
  val nameAndStreet = listOfNotNull(place.name.trim().takeIf { it.isNotEmpty() }, place.street?.trim()?.takeIf { it.isNotEmpty() })
    .joinToString(", ")
  val city = place.city?.trim().orEmpty()
  return if (city.isEmpty()) nameAndStreet else "$nameAndStreet — $city"
}

@Composable
private fun Actions(
  onWiderRadius: (() -> Unit)?,
  widerRadiusLabel: String?,
  onAddNew: () -> Unit,
  isLoggedIn: Boolean,
) {
  Column(modifier = Modifier.padding(top = Spacing.xs)) {
    if (onWiderRadius != null && widerRadiusLabel != null) {
      TextButton(onClick = onWiderRadius) {
        Text(stringResource(R.string.store_picker_wider_radius, widerRadiusLabel))
      }
    }
    // Založení nového obchodu vyžaduje přihlášení (docs/reputace.md, T1) — anonymovi se
    // nenabízí slepá ulička formuláře končící UNAUTHORIZED, jen vysvětlení.
    if (isLoggedIn) {
      TextButton(onClick = onAddNew) { Text(stringResource(R.string.store_picker_add_new_action)) }
    } else {
      Text(
        stringResource(R.string.store_picker_hint_anonymous),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun StatusLine(text: String, loading: Boolean) {
  Row(
    modifier = Modifier.padding(top = Spacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Text, který říká, PROČ to nejde, a tlačítko, kterým to jde spravit. */
@Composable
private fun ExplainedAction(text: String, actionLabel: String, onAction: () -> Unit) {
  Column(modifier = Modifier.padding(top = Spacing.sm)) {
    // Nikdy chybovou barvou — chybějící poloha ani signál není chyba uživatele a červený text
    // by u zápisu ceny vypadal jako by se něco pokazilo nenávratně.
    Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onAction) { Text(actionLabel) }
  }
}
