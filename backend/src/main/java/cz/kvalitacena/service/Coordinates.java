package cz.kvalitacena.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Zaokrouhlování zeměpisných souřadnic přijatých od klienta, než se pošlou dál (Nominatimu,
 * do `nearbyStores`) — nikdy pro souřadnice UKLÁDANÉ jako fakt o provozovně
 * (`core.store.lat/lon`), ty musí zůstat přesné (docs/soukromi.md).
 */
public final class Coordinates {

  private Coordinates() {
  }

  /**
   * Zaokrouhlí na daný počet desetinných míst deterministicky přes {@link BigDecimal} — na
   * rozdíl od {@code Math.round(value * 10^n) / 10^n} netrpí binární nepřesností {@code double}.
   * {@code HALF_UP} je jen konzistentní volba, na výsledku (adresa/rádius vyhledávání) nezáleží,
   * jak přesně se půlka zaokrouhlí.
   */
  public static double round(double value, int decimals) {
    return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
  }
}
