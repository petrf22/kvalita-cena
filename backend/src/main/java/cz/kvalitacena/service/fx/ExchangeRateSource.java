package cz.kvalitacena.service.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Zdroj syrového kurzovního lístku — {@link CnbRateSource} pro drtivou většinu měn a
 * {@link NbsRateSource} pro RSD, které ČNB na lístku nemá (ověřeno živě proti api.cnb.cz, viz
 * plán expanze). {@link ExchangeRateSyncService} injektuje {@code List<ExchangeRateSource>} a
 * slučuje řádky ze všech zdrojů — stejný vzor jako {@code CompanyIdValidators}/
 * {@code CompanyRegistries} u registrů IČO, ne přepis volajícího kódu při dalším zdroji.
 */
public interface ExchangeRateSource {

  /** Nálepka do {@code fx.exchange_rate.source} — odkud konkrétní řádek přišel (CNB/NBS). */
  String name();

  /** Lístek platný pro konkrétní den — prázdný seznam, když zdroj neodpoví nebo pro den nic nemá (víkend/svátek). */
  List<FxRateRow> fetchDay(LocalDate date);

  /** Celoroční historie — jeden request místo desítek při backfillu/velké mezeře. */
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
