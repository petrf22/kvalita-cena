package cz.kvalitacena.ui.common

import cz.kvalitacena.network.OsmStoreCandidate
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

  /**
   * [newProduct] je opravdu nově založené zboží, ne existující položka vybraná z nabídky
   * podobných (ProductFormViewModel.usingExisting) — párování řádku účtenky to posílá serveru
   * jako `matchReceiptLine(newProduct)`.
   */
  var newProductCreated: Boolean = false

  /** Kód naskenovaný v režimu „vrátit kód" (ROUTE_SCAN_CODE) — vyzvedne ho formulář zboží. */
  var scannedCode: String? = null
  /** Napsaný název, kterým uživatel našel existující lokální položku; odešle se až s cenou. */
  var productAlias: String? = null

  /**
   * Provozovna vybraná z nabídky OpenStreetMap — StoreFormScreen z ní předvyplní celý formulář
   * (StoreFormViewModel.applyOsmCandidate). Stejný mezikrok jako newStore/newProduct výš,
   * jen opačným směrem: tenhle jde DO formuláře, ne z něj.
   */
  var osmStoreCandidate: OsmStoreCandidate? = null

  /** Povinný text zdroje/licence (ODbL) k [osmStoreCandidate] — formulář ho MUSÍ zobrazit. */
  var osmStoreAttribution: String? = null

  /** Stejný vzor, ale pro editaci existující provozovny (StoreFormScreen storeId != null) — StoreDetailScreen ho vyzvedne. */
  var updatedStore: Store? = null

  /** Stejný vzor, ale pro editaci existujícího zboží (ProductFormScreen productId != null) — ProductDetailScreen ho vyzvedne. */
  var updatedProduct: Product? = null

  /**
   * Tlačítko "Hledat ceny tohoto zboží" na PriceEntryScreen (naskenovaný kód, nebo název u
   * vstupu z detailu) — záložka Hledat nemá vlastní route argument, protože by ji rozbila
   * `popUpTo(startDestination) { inclusive = true }` ve spodní liště (MainActivity), stejný
   * in-memory mezikrok jako newStore/newProduct výš.
   */
  var searchQuery: String? = null
}
