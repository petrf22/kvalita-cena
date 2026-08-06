package cz.kvalitacena.controller;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PricePoint(LocalDate day, BigDecimal priceAmount, BigDecimal unitPrice, int nObs, int storeCount) {
}
