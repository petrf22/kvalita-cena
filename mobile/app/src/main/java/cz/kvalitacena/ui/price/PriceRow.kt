package cz.kvalitacena.ui.price

/**
 * Jeden řádek formuláře "(druh ceny, částka)" — u regálu bývá cena napsaná i dvakrát/třikrát
 * (běžná, klubová, množstevní), viz `PriceEntryViewModel.priceRows`. Částky jako `String` kvůli
 * rozepsanému vstupu (`SingleLineTextField`), ne kvůli přenosu na server — ten dělá
 * `PriceRowValidation.toObservationPriceInputs`. `id` je stabilní identifikátor pro `key(row.id)`
 * v `LazyColumn`/`forEach` — NENÍ index (odebrání prostředního řádku by ho jinak posunulo).
 */
data class PriceRow(
  val id: Long,
  val priceKind: String = "REGULAR",
  val priceAmount: String = "",
  val multibuyQty: String = "",
  val multibuyTotal: String = "",
  // Platnost akce (jen PROMO, docs/datovy-model.md) — ISO datum (yyyy-MM-dd) nebo "" = nezadáno.
  val promoValidFrom: String = "",
  val promoValidTo: String = "",
)
