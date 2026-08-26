package cz.kvalitacena.ui.price

import cz.kvalitacena.network.ObservationPriceInput
import cz.kvalitacena.ui.common.SELECTABLE_PRICE_KINDS
import java.time.LocalDate

/**
 * Čistá validace/dopočty pro víc cen z jedné cenovky — mimo Compose, ať jde otestovat JUnitem
 * bez instrumentace (stejný vzor jako `ui/store/StoreFormValidation.kt`). Zrcadlí backendovou
 * dávkovou validaci v `PriceObservationService.submit()` (docs/datovy-model.md) a webovou
 * `shared/price-rows.ts`, aby uživatel dostal chybu hned ve formuláři, ne až po odeslání.
 */

/**
 * Desetinná čárka i tečka se přijímají obě jako oddělovač (viz `PriceEntryScreen`). Samotná "."
 * NENÍ platná částka — dřív tudy propadávala (`PriceEntryViewModel.submit()` dřív tiše skončila
 * na `toDoubleOrNull() ?: return`, tlačítko přitom svítilo, protože `isNotBlank()` "." bere).
 */
fun parseAmount(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()

/**
 * Platnost akce smí mít jen PROMO, `promoValidFrom` nesmí být v budoucnu (zapisuje se cena,
 * kterou uživatel VIDĚL v regále, ne cena z letáku — docs/rozvoj.md) a `od` nesmí být po `do`.
 * Zrcadlí `PriceObservationService.validatePromoValidity` na backendu a web `price-rows.ts`.
 */
fun isPromoValidityValid(row: PriceRow): Boolean {
  if (row.priceKind != "PROMO") {
    return row.promoValidFrom.isBlank() && row.promoValidTo.isBlank()
  }
  val from = row.promoValidFrom.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
  val to = row.promoValidTo.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
  if (row.promoValidFrom.isNotBlank() && from == null) return false
  if (row.promoValidTo.isNotBlank() && to == null) return false
  if (from != null && to != null && from.isAfter(to)) return false
  if (from != null && from.isAfter(LocalDate.now())) return false
  return true
}

fun isPriceRowValid(row: PriceRow): Boolean {
  if (!isPromoValidityValid(row)) return false
  return if (row.priceKind == "MULTIBUY") {
    val qty = row.multibuyQty.toIntOrNull()
    qty != null && qty >= 2 && parseAmount(row.multibuyTotal) != null
  } else {
    parseAmount(row.priceAmount) != null
  }
}

/** První druh ceny, který se v seznamu opakuje (v pořadí, ve kterém ho uživatel vidí podruhé), nebo null. */
fun duplicatePriceKind(rows: List<PriceRow>): String? {
  val seen = mutableSetOf<String>()
  for (row in rows) {
    if (!seen.add(row.priceKind)) return row.priceKind
  }
  return null
}

fun arePriceRowsValid(rows: List<PriceRow>): Boolean =
  rows.isNotEmpty() && duplicatePriceKind(rows) == null && rows.all(::isPriceRowValid)

/** Nabídka druhu ceny pro konkrétní řádek — vyloučí druhy použité v OSTATNÍCH řádcích, takže
 *  duplicitu nejde ve formuláři vyrobit vůbec, jen ji hlídá server jako pojistka pro souběh. */
fun availablePriceKinds(rows: List<PriceRow>, current: String): List<String> {
  val usedByOthers = rows.filter { it.priceKind != current }.map { it.priceKind }.toSet()
  return SELECTABLE_PRICE_KINDS.filter { it == current || it !in usedByOthers }
}

/** První nepoužitý druh ceny pro nový řádek přidaný tlačítkem "+" — ať uživatel nemusí sám
 *  přepínat pryč od druhu, který už v dávce je. */
fun firstAvailablePriceKind(rows: List<PriceRow>): String {
  val used = rows.map { it.priceKind }.toSet()
  return SELECTABLE_PRICE_KINDS.firstOrNull { it !in used } ?: SELECTABLE_PRICE_KINDS.first()
}

/**
 * Řádky → vstup pro submitObservations. U MULTIBUY se `priceAmount` NEposílá (server ho odvodí
 * z `multibuyTotal`) a naopak u ostatních druhů se neposílá `multibuyQty`/`multibuyTotal`.
 */
fun toObservationPriceInputs(rows: List<PriceRow>): List<ObservationPriceInput> =
  rows.map { row ->
    if (row.priceKind == "MULTIBUY") {
      ObservationPriceInput(
        priceKind = row.priceKind,
        multibuyQty = row.multibuyQty.toIntOrNull(),
        multibuyTotal = parseAmount(row.multibuyTotal),
      )
    } else if (row.priceKind == "PROMO") {
      ObservationPriceInput(
        priceKind = row.priceKind,
        priceAmount = parseAmount(row.priceAmount),
        promoValidFrom = row.promoValidFrom.takeIf { it.isNotBlank() },
        promoValidTo = row.promoValidTo.takeIf { it.isNotBlank() },
      )
    } else {
      ObservationPriceInput(priceKind = row.priceKind, priceAmount = parseAmount(row.priceAmount))
    }
  }
