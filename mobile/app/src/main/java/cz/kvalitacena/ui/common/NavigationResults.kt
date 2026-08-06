package cz.kvalitacena.ui.common

import cz.kvalitacena.network.Product
import cz.kvalitacena.network.Store

/**
 * Přenos výsledku "právě založeno" z formulářů katalogu (StoreFormScreen/ProductFormScreen)
 * zpátky na obrazovku, která na něj čeká (PriceEntryScreen) — po návratu (`popBackStack`) ho
 * obrazovka v `LaunchedEffect(Unit)` vyzvedne a vynuluje. `NavBackStackEntry.savedStateHandle`
 * by tu vyžadoval Parcelable/Bundle-kompatibilní typy, které DTO třídy (kotlinx.serialization,
 * ne @Parcelize) nemají — pro tenhle jednorázový "vrať se s výsledkem" mezikrok je in-memory
 * singleton jednodušší a nespadne na serializaci. Appka nemá víc než jednu takovou obrazovku
 * najednou otevřenou, takže sdílený stav nekoliduje.
 */
object NavigationResults {
  var newStore: Store? = null
  var newProduct: Product? = null
}
