package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Import účtenek (docs/rozvoj.md, „Načtení celé účtenky"). Limity patří do konfigurace,
 * ne natvrdo do kódu — stejné pravidlo jako u {@link CatalogProperties}/{@code ModerationProperties}
 * (docs/reputace.md).
 */
@Component
@ConfigurationProperties(prefix = "app.receipt")
@Data
public class ReceiptProperties {

  /** Nad tímhle počtem řádků to není účtenka z nákupu, ale pokus něco nasypat do fronty. */
  private int maxLinesPerReceipt;

  /** Kolik účtenek smí jeden účet nahrát za den. */
  private int maxPerDay;

  /**
   * Tolerance kontrolního součtu. Na reálných účtenkách sedí Σ(položky + slevy) na vytištěné
   * „Celkem" na haléř, takže tolerance je jen pojistka na jeden zaokrouhlovací řádek — ne
   * prostor pro rozsypané OCR. Účtenka, která se do ní nevejde, se nezapíše do cen.
   */
  private BigDecimal totalTolerance;

  /**
   * Kolik dní se nahraná účtenka drží. Na rozdíl od cenového zápisu se nepseudonymizuje, ale
   * MAŽE (docs/soukromi.md, „Účtenka"): ceny z ní už žijí ve vlastních observacích a zbytek
   * je přehled cizího nákupu, který bez majitele nemá hodnotu.
   */
  private int retentionDays;
}
