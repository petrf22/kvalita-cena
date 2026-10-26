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
