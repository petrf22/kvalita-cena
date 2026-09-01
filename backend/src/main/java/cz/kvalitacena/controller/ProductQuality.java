package cz.kvalitacena.controller;

import java.math.BigDecimal;

/** Průměr hvězdiček 1,00–5,00 (5 nejlepší). {@code average} je null, dokud nikdo nehodnotil. */
public record ProductQuality(BigDecimal average, int count) {

  public static final ProductQuality EMPTY = new ProductQuality(null, 0);
}
