package cz.kvalitacena.ui.common

/**
 * Normalizace čárového kódu na klientovi — jen číslice, bez vedoucích nul. Server normalizuje
 * na GTIN-14 (`backend/.../GtinNormalization.java`), appka tuhle logiku neduplikuje, jen
 * sjednocuje zápisy STEJNÉHO kódu pro cache klíč (`network/GraphQlClient.kt`) a pro porovnání
 * zadaného kódu s nabídnutým OFF kandidátem (`ui/product/ProductFormViewModel.kt`), zrcadlo
 * webu (`frontend/src/app/shared/gtin.ts`).
 */
fun normalizeCode(code: String): String {
  val digits = code.filter { it.isDigit() }
  return digits.trimStart('0').ifEmpty { if (digits.isNotEmpty()) "0" else "" }
}

/** Délky GTIN podle GS1: EAN-8, UPC-A (GTIN-12), EAN-13, GTIN-14. */
private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

/**
 * Prefixy GTIN-13 s omezeným oběhem (GS1 Restricted Circulation Number) a kupóny: 20–29
 * (vnitroobchodní kódy, typicky etiketa váhového zboží s cenou uvnitř), 02/04 (totéž ve
 * 13místném tvaru UPC-A s číselnou soustavou 2/4), 05 (kupóny UPC), 98/99 (kupóny).
 */
private val RESTRICTED_GTIN13_PREFIXES = listOf("2", "02", "04", "05", "98", "99")

/**
 * Má [code] délku GTIN a správnou kontrolní číslici GS1? Platí jen 8/12/13/14 číslic, váhy
 * 3-1 zprava bez kontrolní číslice, `(10 - součet % 10) % 10`. Zrcadlo webu
 * (`frontend/src/app/shared/gtin.ts`).
 *
 * Samotná kontrolní číslice NEstačí k tomu, aby se kód směl použít jako čárový kód zboží —
 * projde jí i etiketa váhového zboží s cenou uvnitř. K tomu je [isGlobalGtin].
 * Mezery se neořezávají — viz [globalGtinOrNull].
 */
fun isValidGtin(code: String): Boolean {
  // '0'..'9', ne Char.isDigit() — ten pustí i číslice jiných písem (arabsko-indické apod.).
  if (code.length !in GTIN_LENGTHS || !code.all { it in '0'..'9' }) return false
  val sum = code.dropLast(1).reversed().foldIndexed(0) { index, acc, digit ->
    acc + (digit - '0') * if (index % 2 == 0) 3 else 1
  }
  return (10 - sum % 10) % 10 == code.last() - '0'
}

/**
 * Kód s omezeným oběhem (GS1 Restricted Circulation) — platí jen uvnitř obchodu nebo řetězce,
 * do katalogu jako EAN nepatří. EAN-8 začínající 0 nebo 2; 12/13/14 číslic se převede na
 * 13 (12 doplnit nulou zleva, u 14 vzít posledních 13 — prefix GS1 stojí hned za indikátorem
 * balení) a vyřadí se prefixy [RESTRICTED_GTIN13_PREFIXES]. Stejně jako web i backend
 * (`GtinNormalization`): GTIN-14 nad kódem s omezeným oběhem GS1 nedovoluje, takže takový
 * kód je chyba čtení nebo vnitroobchodní kód, ne globální EAN.
 * Předpokládá kód, který už prošel [isValidGtin].
 */
private fun isRestrictedCirculation(code: String): Boolean {
  if (code.length == 8) return code[0] == '0' || code[0] == '2'
  val gtin13 = when (code.length) {
    12 -> "0$code"
    13 -> code
    14 -> code.substring(1)
    else -> return false
  }
  return RESTRICTED_GTIN13_PREFIXES.any(gtin13::startsWith)
}

/**
 * Smí se [code] použít jako globální čárový kód zboží (GTIN)? Platná kontrolní číslice
 * ([isValidGtin]) a zároveň ne kód s omezeným oběhem. Jediná brána pro kód z řádku účtenky
 * (`ReceiptLine.internalCode`) — pro předání do formuláře nového zboží i pro zobrazení
 * „EAN" u řádku. Stejné pravidlo má server (`backend/.../service/GtinNormalization.java`)
 * a web (`frontend/src/app/shared/gtin.ts`).
 *
 * Proč: `internalCode` je u některých účtenek EAN, u jiných vnitroobchodní kód řetězce
 * (`STORE_INTERNAL`, platí jen v rámci řetězce) nebo kód váhové etikety. Ten by se jinak
 * uložil jako globální GTIN cizího zboží. Délka sama nestačí, interní kódy mívají i 8 nebo
 * 13 číslic.
 *
 * Záměrně přísnější než [cz.kvalitacena.ui.scan.isManualBarcodeValid] (8–14 číslic bez
 * kontrolní číslice): ruční zadání nechává rozhodnutí na serveru, kód z účtenky si ale nikdo
 * neopisoval z obalu, takže ho appka musí ověřit sama.
 */
fun isGlobalGtin(code: String): Boolean = isValidGtin(code) && !isRestrictedCirculation(code)

/** [code] bez okrajových mezer, když je to globální GTIN ([isGlobalGtin]), jinak null. */
fun globalGtinOrNull(code: String?): String? = code?.trim()?.takeIf(::isGlobalGtin)
