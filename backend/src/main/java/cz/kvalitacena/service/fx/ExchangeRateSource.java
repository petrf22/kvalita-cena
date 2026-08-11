package cz.kvalitacena.service.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Zdroj syrového kurzovního lístku — dnes jen {@link CnbRateSource}, ale rozhraní odděleně od
 * {@link ExchangeRateSyncService}, kdyby jednou přibyl další zdroj (obdoba {@code
 * CompanyRegistry} u ARES/registrů IČO).
 */
public interface ExchangeRateSource {

  /** Lístek platný pro konkrétní den (ČNB {@code exrates/daily}) — prázdný seznam, když zdroj neodpoví nebo pro den nic nemá (víkend/svátek). */
  List<FxRateRow> fetchDay(LocalDate date);

  /** Celoroční historie (ČNB {@code exrates/daily-year}) — jeden request místo desítek při backfillu/velké mezeře. */
  List<FxRateRow> fetchYear(int year);

  /**
   * Jeden řádek kurzovního lístku, ještě NEnormalizovaný — {@code rate} platí pro {@code amount}
   * jednotek měny (ČNB kótuje některé měny po stovkách, např. HUF). Normalizaci na
   * "kolik CZK za 1 jednotku" dělá až volající (ExchangeRateSyncService), aby zdroj zůstal
   * čistě transportní vrstvou.
   */
  record FxRateRow(String currencyCode, int amount, LocalDate validFor, BigDecimal rate) {
  }
}
