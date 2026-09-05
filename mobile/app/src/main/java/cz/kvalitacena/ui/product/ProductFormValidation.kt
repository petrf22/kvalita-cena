package cz.kvalitacena.ui.product

import cz.kvalitacena.network.ExternalProductCandidate
import cz.kvalitacena.network.Product
import cz.kvalitacena.network.ProductName
import cz.kvalitacena.network.ProductNameInput
import cz.kvalitacena.network.UpdateProductInput

/**
 * Čistá logika mimo Compose/ViewModel, ať jde otestovat JUnitem (stejný vzor jako
 * StoreFormValidation/PriceChartGeometry). Protějšek webové
 * `features/product-form/product-form-validation.ts#pendingPhotoUploads`. Generický přes typ
 * odkazu na vybraný soubor ([android.net.Uri] ve ViewModelu, `String` v testu) — funkce jen
 * určuje pořadí a druh, o obsah se nezajímá.
 *
 * Zbytek souboru je protějšek editace existujícího zboží (`productFormDefaults`/
 * `netContentForUpdateSubmit`/`buildUpdateProductInput`). ViewModely/obrazovky na `mobile/`
 * se automatizovaně netestují (`mobile/CLAUDE.md`, "Konvence") — logika je proto co nejtenčí a co
 * nejblíž webovému protějšku, který otestovaný je.
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

data class ProductFormDefaults(
  val name: String,
  /** Všechny známé názvy po jazycích (i ty z OFF) — zdroj pro diff, viz [changedNames]. */
  val names: Map<String, String>,
  val brandName: String,
  val categoryId: String?,
  val unitBase: String,
  val netContentValue: Double?,
  val piecesInPack: Int?,
  val isVariableWeight: Boolean,
)

/** Gramáž/objem server nese v G/ML (OffNetContentConverter), appka vždy v kg/l — stejný převod
 *  jako [ProductFormViewModel.offDefaultsFrom] pro OFF kandidáta. */
private fun toFormNetContentValue(value: Double?, uom: String?): Double? {
  if (value == null || uom == null) return null
  return when (uom) {
    "G", "ML" -> value / 1000
    "KG", "L" -> value
    else -> null
  }
}

fun productFormDefaultsFrom(product: Product, lang: String): ProductFormDefaults {
  val names = namesByLang(product.names)
  return ProductFormDefaults(
    // Pole "Název" je vždy v jazyce appky (docs/lokalizace.md). Product.name sem nejde dosadit
    // naslepo — může to být fallback z jiného jazyka (viz Product.nameLang).
    name = names[lang].orEmpty(),
    names = names,
    brandName = product.brand?.name.orEmpty(),
    categoryId = product.category.id,
    unitBase = product.unitBase,
    netContentValue = toFormNetContentValue(product.netContentValue, product.netContentUom),
    piecesInPack = product.piecesInPack,
    isVariableWeight = product.isVariableWeight,
  )
}

/**
 * Názvy po jazycích na mapu jazyk→název. Položky bez jazyka se zahazují — u "hlavního" názvu
 * z OFF se jazyk poznat nedá (docs/lokalizace.md) a formulář by ho neměl kam zařadit.
 */
fun namesByLang(names: List<ProductName>): Map<String, String> =
  names.mapNotNull { entry -> entry.lang?.let { it to entry.name } }.toMap()

/**
 * Které z ostatních jazyků poslat serveru: jen ty, jejichž text se liší od zdrojové hodnoty.
 * U OFF kandidáta je to podmínka, ne optimalizace — poslat zpátky nezměněný název z OFF by
 * znamenalo zapsat cizí data do core.product_name, což ODbL share-alike zakazuje
 * (kořenový CLAUDE.md, past OFF kandidáta).
 */
fun changedNames(
  current: Map<String, String>,
  source: Map<String, String>,
  excludeLang: String,
): List<ProductNameInput> = current.entries
  .filter { (lang, name) -> lang != excludeLang && name.isNotBlank() && name.trim() != source[lang] }
  .map { (lang, name) -> ProductNameInput(lang = lang, name = name.trim()) }

/**
 * Názvy z OFF kandidáta pro předvyplnění. Pole "Název" dostane hodnotu, JEN když ji OFF má
 * v jazyce appky — jinak zůstane prázdné a cizojazyčná varianta se ukáže v sekci ostatních
 * jazyků. Předvyplnit ho německým textem by znamenalo uložit němčinu jako český název, tedy
 * přesně to, co se touhle změnou opravuje.
 */
fun offNamesFrom(candidate: ExternalProductCandidate): Map<String, String> =
  namesByLang(candidate.names)

/** Server implied UOM konvence (docs/datovy-model.md): MASS→kg, VOLUME→l, COUNT→ks. */
private fun impliedUom(unitBase: String): String? = when (unitBase) {
  "MASS" -> "KG"
  "VOLUME" -> "L"
  "COUNT" -> "PCS"
  else -> null
}

/**
 * Gramáž/objem pro UpdateProductInput — MUSÍ se posílat vždy jako dvojice, i když se změnil jen
 * unitBase/isVariableWeight (CatalogEditService.updateProduct přepočítává netContentBase
 * v jediném bloku podmíněném tím, že aspoň jedno z trojice netContentValue/netContentUom/
 * isVariableWeight přišlo nenulové — samotný unitBase by netContentBase pro novou jednotku
 * nedopočítal). Shoda s prefillem (nebo nic nezadáno) → obojí null; jinak obojí z formuláře.
 */
private fun netContentForUpdateSubmit(
  netContentValue: Double?,
  unitBase: String,
  isVariableWeight: Boolean,
  defaults: ProductFormDefaults,
): Pair<Double?, String?> {
  val changed = unitBase != defaults.unitBase ||
    isVariableWeight != defaults.isVariableWeight ||
    (netContentValue == null) != (defaults.netContentValue == null) ||
    (netContentValue != null && defaults.netContentValue != null &&
      kotlin.math.abs(netContentValue - defaults.netContentValue) >= 1e-9)
  if (!changed) return null to null
  return (if (isVariableWeight) null else netContentValue) to impliedUom(unitBase)
}

/**
 * Patch nad core.product_user_edit z aktuálního stavu formuláře proti prefillu — zrcadlo
 * `buildUpdateProductInput` na webu. Pole beze změny se posílají jako null, vyprázdnění pošle
 * clear*.
 */
fun buildUpdateProductInput(
  name: String,
  nameLang: String,
  names: List<ProductNameInput>,
  brandName: String,
  categoryId: String,
  unitBase: String,
  netContentValue: Double?,
  piecesInPack: Int?,
  isVariableWeight: Boolean,
  defaults: ProductFormDefaults,
): UpdateProductInput {
  val trimmedName = name.trim()
  val trimmedBrand = brandName.trim()
  val (netContent, netContentUom) =
    netContentForUpdateSubmit(netContentValue, unitBase, isVariableWeight, defaults)
  return UpdateProductInput(
    name = if (trimmedName == defaults.name) null else trimmedName,
    nameLang = nameLang,
    names = names,
    brandName = if (trimmedBrand.isEmpty() || trimmedBrand == defaults.brandName) null else trimmedBrand,
    clearBrand = trimmedBrand.isEmpty() && defaults.brandName.isNotEmpty(),
    categoryId = if (categoryId == defaults.categoryId) null else categoryId,
    unitBase = if (unitBase == defaults.unitBase) null else unitBase,
    netContentValue = netContent,
    netContentUom = netContentUom,
    piecesInPack = if (piecesInPack == defaults.piecesInPack) null else piecesInPack,
    clearPiecesInPack = piecesInPack == null && defaults.piecesInPack != null,
    isVariableWeight = if (isVariableWeight == defaults.isVariableWeight) null else isVariableWeight,
  )
}
