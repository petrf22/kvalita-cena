package cz.kvalitacena.controller;

import java.time.LocalDate;
import java.util.List;

/** Zobrazovací měny a stav kurzovního lístku ČNB (docs/lokalizace.md) — pro kartu "Zdroje dat" v Nastavení. */
public record FxInfo(List<String> displayCurrencies, LocalDate latestRateDate, String attribution) {
}
