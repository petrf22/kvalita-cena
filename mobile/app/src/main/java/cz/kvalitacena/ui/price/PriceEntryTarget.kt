package cz.kvalitacena.ui.price

/**
 * Odkud PriceEntryScreen ví, jaké zboží zapisuje — buď z naskenovaného čárového kódu (sken),
 * nebo z detailu produktu nalezeného hledáním ("Zapsat cenu" na ProductDetailScreen).
 */
sealed interface PriceEntryTarget {
  data class ByBarcode(val barcode: String) : PriceEntryTarget
  data class ById(val productId: String) : PriceEntryTarget
}
