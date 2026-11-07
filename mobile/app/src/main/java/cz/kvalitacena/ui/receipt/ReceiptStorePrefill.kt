package cz.kvalitacena.ui.receipt

import cz.kvalitacena.network.Receipt

/**
 * Údaje z hlavičky účtenky, kterými se předvyplní nový obchod (StoreFormViewModel
 * .applyReceiptPrefill), ať uživatel neopisuje, co už stroj přečetl. Jsou to vlastní data
 * uživatele, ne OSM — obchod z nich vzniká s `data_origin = OWN`.
 */
data class StorePrefill(
  val name: String?,
  val street: String?,
  val postalCode: String?,
  val city: String?,
  val ico: String?,
)

/** PSČ a město — stejný regex, kterým je z účtenky čte worker (tools/uctenky/profiles). */
private val POSTAL_CITY = Regex("""^(\d{3}\s?\d{2})\s+(\D.*)$""")

/** `null`, když hlavička účtenky nenese nic, čím by šlo formulář předvyplnit. */
fun receiptStorePrefill(receipt: Receipt): StorePrefill? {
  val address = splitReceiptAddress(receipt.storeAddressRaw)
  val prefill = StorePrefill(
    name = receipt.storeNameRaw?.trim()?.ifBlank { null },
    street = address.street,
    postalCode = address.postalCode,
    city = address.city,
    ico = receipt.storeIcoRaw?.trim()?.ifBlank { null },
  )
  return prefill.takeIf { listOf(it.name, it.street, it.postalCode, it.city, it.ico).any { part -> part != null } }
}

/**
 * Co z hlavičky účtenky smí do formuláře obchodu, který už předvyplnil kandidát z OpenStreetMap
 * (StoreFormViewModel.applyReceiptPrefill): IČO, které OSM nezná. Název a adresa zůstávají
 * z OSM — přečtený tisk by jinak přepsal údaje, které si uživatel v nabídce vybral, a obchod by
 * se přitom dál ukládal jako `OSM_DERIVED`.
 *
 * Výjimka: kandidát bez města (`addr:city` v OSM u provozoven často chybí) — formulář by nešel
 * uložit, takže se vezme CELÁ adresa z účtenky (ulice, PSČ, město), ne jen město, aby nevznikla
 * smíchaná adresa ze dvou zdrojů. Stejně to dělá web (`osmCandidateFormValues`). Název zůstává
 * z OSM. `null`, když není co převzít.
 */
fun StorePrefill.besideOsmCandidate(candidateHasCity: Boolean): StorePrefill? {
  val address = !candidateHasCity && city != null
  if (!address && ico == null) return null
  return StorePrefill(
    name = null,
    street = if (address) street else null,
    postalCode = if (address) postalCode else null,
    city = if (address) city else null,
    ico = ico,
  )
}

/**
 * Adresa obchodu z hlavičky účtenky pro nabídku z OpenStreetMap (`osmStoresAtAddress`) a název,
 * podle kterého server řadí podobné kandidáty nahoru. Je to adresa OBCHODU vytištěná na účtence,
 * ne poloha uživatele (docs/soukromi.md).
 */
data class ReceiptOsmQuery(val name: String?, val street: String, val city: String, val postalCode: String?)

/**
 * `null`, když na účtence chybí město nebo ulice. Bez ulice by Nominatim vrátil střed obce
 * a nabídka by ukázala náhodné obchody z centra jako obchody „u adresy z účtenky" — server se
 * proto bez ulice ani neptá.
 */
fun receiptOsmQuery(receipt: Receipt): ReceiptOsmQuery? {
  val prefill = receiptStorePrefill(receipt) ?: return null
  val city = prefill.city ?: return null
  val street = prefill.street ?: return null
  return ReceiptOsmQuery(name = prefill.name, street = street, city = city, postalCode = prefill.postalCode)
}

data class ReceiptAddress(val street: String?, val postalCode: String?, val city: String?)

/**
 * Rozloží `storeAddressRaw` zpátky na části. Server dostává od workeru jeden řetězec ve tvaru
 * `"[ulice, ]PSČ Město"`, případně jen `"ulice"`, když parser PSČ nenašel
 * (tools/uctenky/result.py) — PSČ a město tam chodí vždy spolu. Webový protějšek:
 * frontend features/receipts/receipt-store-prefill.ts.
 */
fun splitReceiptAddress(raw: String?): ReceiptAddress {
  val text = raw?.trim()?.ifBlank { null } ?: return ReceiptAddress(null, null, null)
  val comma = text.lastIndexOf(", ")
  val head = if (comma >= 0) text.substring(0, comma).trim() else null
  val tail = if (comma >= 0) text.substring(comma + 2).trim() else text
  val match = POSTAL_CITY.matchEntire(tail) ?: return ReceiptAddress(text, null, null)
  return ReceiptAddress(
    street = head?.ifBlank { null },
    postalCode = match.groupValues[1],
    city = match.groupValues[2].trim(),
  )
}
