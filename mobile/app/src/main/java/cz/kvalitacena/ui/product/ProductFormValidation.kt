package cz.kvalitacena.ui.product

/**
 * Čistá logika mimo Compose/ViewModel, ať jde otestovat JUnitem (stejný vzor jako
 * StoreFormValidation/PriceChartGeometry). Protějšek webové
 * `features/product-form/product-form-validation.ts#pendingPhotoUploads`. Generický přes typ
 * odkazu na vybraný soubor ([android.net.Uri] ve ViewModelu, `String` v testu) — funkce jen
 * určuje pořadí a druh, o obsah se nezajímá.
 */
data class PendingPhotoUpload<T>(val value: T, val kind: String)

/**
 * Které vybrané fotky nahrát po založení zboží a v jakém pořadí — obě volitelné. Fotka zboží
 * jde první, ať dostane sortOrder 0 (hlavní fotka záznamu, backend MediaService.upload),
 * etiketa až po ní.
 */
fun <T> pendingPhotoUploads(itemPhoto: T?, labelPhoto: T?): List<PendingPhotoUpload<T>> {
  val uploads = mutableListOf<PendingPhotoUpload<T>>()
  itemPhoto?.let { uploads += PendingPhotoUpload(it, "ITEM") }
  labelPhoto?.let { uploads += PendingPhotoUpload(it, "LABEL") }
  return uploads
}
