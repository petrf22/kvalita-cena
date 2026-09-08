package cz.kvalitacena.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Designové tokeny rozestupů. Zdroj pravdy pro hodnoty je `docs/design.md`, ne tenhle soubor —
 * nová hodnota se nejdřív zapíše tam, teprve pak sem. Řada je shodná s webovými `--kc-space-*`
 * ve `frontend/src/styles.css`, aby se web a mobil vizuálně nerozešly.
 *
 * Obyčejný `object`, ne `CompositionLocal` — rozestupy se v téhle appce nemění podle tématu ani
 * podle velikosti obrazovky, takže by šlo o režii bez užitku (stejný důvod, proč je DI ruční
 * přes `AppContainer` a ne Hilt).
 *
 * Rozměry komponent (výška náhledu fotky, mapy) do škály NEpatří a zůstávají u své komponenty.
 */
object Spacing {
  /** Mezera mezi popiskem a hodnotou. */
  val xs = 4.dp

  /** Mezi prvky uvnitř řádku, `spacedBy` v `Row`/`Column`. */
  val sm = 8.dp

  /** Mezi souvisejícími bloky formuláře. */
  val md = 12.dp

  /** Vnitřní odsazení karty, mezera mezi sekcemi. */
  val lg = 16.dp

  /** Odsazení obrazovky, mezera mezi nesouvisejícími bloky. */
  val xl = 24.dp

  /** Velké oddělení, prázdný stav. */
  val xxl = 32.dp
}
