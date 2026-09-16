package cz.kvalitacena.ui.common

import cz.kvalitacena.network.OsmStoreCandidate
import cz.kvalitacena.network.Store

/** Obchod z okolí i se vzdáleností spočítanou z PŘESNÉ polohy, kterou zná jen telefon. */
data class NearbyStore(val store: Store, val distanceMeters: Double)

/** Kandidát z OSM i se vzdáleností přepočítanou z přesné polohy (server ji zná jen zaokrouhleně). */
data class NearbyOsmStore(val candidate: OsmStoreCandidate, val distanceMeters: Double)

/**
 * Stav hledání obchodů v okolí. Čtyři stavy z `docs/design.md` (načítání, prázdno, chyba,
 * offline) tu mají navíc rozpad podle DŮVODU, proč appka polohu nemá — právě ten určuje, co má
 * uživatel udělat, aby uspěl: u [PermissionMissing] povolit přístup, u [LocationDisabled]
 * zapnout polohu v systému, u [LocationTimeout] počkat a zkusit znovu. Dřív se všechny tři
 * slily do jediné hlášky "Polohu se nepodařilo zjistit", která neradila nic.
 */
sealed interface NearbyState {
  /** Ještě se nehledalo (sekce zápisu ceny je sbalená, nebo obrazovka teprve naběhla). */
  data object Idle : NearbyState
  data object Locating : NearbyState
  /** Prázdný seznam znamená "v okruhu opravdu nic", ne "nevíme" — poloha je známá. */
  data class Loaded(val stores: List<NearbyStore>) : NearbyState
  data object PermissionMissing : NearbyState
  data object LocationDisabled : NearbyState
  data object LocationTimeout : NearbyState
  /** Dotaz na server selhal — typicky offline, což je v obchodě běžný stav. */
  data class Failed(val message: UiText) : NearbyState
}

/**
 * Stav nabídky z OpenStreetMap. [Unavailable] je schválně oddělené od prázdného [Loaded] —
 * při výpadku Overpassu by "nic jsme nenašli" svádělo k založení obchodu, který v OSM dávno je.
 */
sealed interface OsmState {
  data object Idle : OsmState
  data object Loading : OsmState
  data class Loaded(val candidates: List<NearbyOsmStore>, val attribution: String) : OsmState
  data object Unavailable : OsmState
  data class Failed(val message: UiText) : OsmState
}
