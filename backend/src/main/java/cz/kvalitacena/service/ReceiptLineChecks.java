package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.QuantityUnit;
import cz.kvalitacena.db.entity.ReceiptLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Kontroly řádku účtenky pro zveřejnění bez spárování (docs/ai.md, „Účtenky"): co není zboží
 * (záloha, vratný obal) a co vypadá na chybu čtení. Obojí jen řídí předvýběr a upozornění
 * v klientovi; server navíc pro „nezboží" nezaloží zboží z účtenky.
 */
@Component
public class ReceiptLineChecks {

  /** Rozdíl {@code množství × cena} proti celkové částce, který se ještě bere jako zaokrouhlení. */
  private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.02");

  private final Pattern nonProduct;

  public ReceiptLineChecks(ReceiptProperties properties) {
    String pattern = properties.getNonProductPattern();
    this.nonProduct = pattern == null || pattern.isBlank() ? null : Pattern.compile(pattern);
  }

  /** Řádek není zboží — {@code app.receipt.non-product-pattern} nad {@code label_norm}. */
  public boolean isNonProduct(ReceiptLine line) {
    String norm = line.getLabelNorm();
    return nonProduct != null && norm != null && nonProduct.matcher(norm).find();
  }

  /**
   * Řádek, na který se má uživatel podívat, než ho zveřejní: chybí cena, cena za jednotku
   * krát množství nedá celkovou částku, nebo z označení zbylo méně než tři písmena.
   */
  public static boolean isSuspicious(ReceiptLine line) {
    BigDecimal unit = line.getUnitPrice();
    BigDecimal total = line.getLineTotal();
    if ((unit == null || unit.signum() <= 0) && (total == null || total.signum() <= 0)) {
      return true;
    }
    if (unit != null && total != null && line.getQuantity() != null && line.getQuantity().signum() > 0) {
      BigDecimal expected = unit.multiply(line.getQuantity());
      // Váhové zboží obchod zaokrouhluje po svém, tam stačí řádová shoda.
      BigDecimal tolerance = line.getQuantityUnit() == QuantityUnit.PCS
          ? TOTAL_TOLERANCE : total.abs().multiply(new BigDecimal("0.02")).max(TOTAL_TOLERANCE);
      if (expected.subtract(total).abs().compareTo(tolerance) > 0) {
        return true;
      }
    }
    String label = line.getLabelDisplay() != null ? line.getLabelDisplay() : line.getLabelRaw();
    return label == null || label.codePoints().filter(Character::isLetter).count() < 3;
  }
}
