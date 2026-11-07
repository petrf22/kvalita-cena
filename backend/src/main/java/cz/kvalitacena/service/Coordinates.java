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
   * Kolik metrů se přidá k hledanému okruhu jako kompenzace za zaokrouhlení polohy na 3
   * desetinná místa ({@link #round}). Mřížka 0,001° je 111 m na šířce a ~71 m na délce
   * (50° s. š.), takže se střed hledání může posunout až o ~66 m — u okruhu 200 m třetina. Bez
   * kompenzace by uživateli vypadl obchod, ve kterém právě stojí. Klient seznam dofiltruje podle
   * skutečné vzdálenosti (docs/soukromi.md), takže navíc přišlých pár řádků nikdo neuvidí.
   * Platí pro nearbyStores i nabídku z OpenStreetMap.
   */
  public static final double ROUNDING_SLACK_M = 70;

  /**
   * Zaokrouhlí na daný počet desetinných míst deterministicky přes {@link BigDecimal} — na
   * rozdíl od {@code Math.round(value * 10^n) / 10^n} netrpí binární nepřesností {@code double}.
   * {@code HALF_UP} je jen konzistentní volba, na výsledku (adresa/rádius vyhledávání) nezáleží,
   * jak přesně se půlka zaokrouhlí.
   */
  public static double round(double value, int decimals) {
    return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
  }

  /**
   * Vzdálenost dvou bodů po povrchu Země v metrech (haversine). Používá se tam, kde vzdálenost
   * nepočítá Postgres přes {@code earth_distance} — tedy u kandidátů z OpenStreetMap, kteří
   * v databázi nikdy nebyli (OverpassPoiService). Kulová aproximace se od elipsoidu liší
   * o desetiny procenta, což je na vzdálenostech v řádu stovek metrů hluboko pod chybou
   * samotného mobilního fixu i pod zaokrouhlením polohy podle docs/soukromi.md.
   */
  public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusM = 6_371_008.8;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * earthRadiusM * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
