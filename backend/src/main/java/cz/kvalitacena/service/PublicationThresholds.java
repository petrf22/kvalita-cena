package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductCreatedVia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kolik potvrzení od JINÝCH přispěvatelů (leave-one-out) potřebuje nový záznam, než se zveřejní
 * (docs/reputace.md, „Práh důvěry pro zveřejnění nového záznamu" a „Zboží z účtenky"). Jedno
 * místo, aby promoce ({@link ProductCatalogService#promoteIfConfirmed}) a výpisy stavu
 * („Moje příspěvky", moderace) nikdy nepočítaly s jiným číslem.
 *
 * <p>Zboží z účtenky má vlastní práh {@code app.receipt.item-confirmations} — v profilu
 * {@code beta} se zveřejňuje hned (0), ruční bezkódové zboží a obchody dál čekají na
 * {@code app.catalog.draft-confirmations}.
 */
@Component
@RequiredArgsConstructor
public class PublicationThresholds {

  private final CatalogProperties catalogProperties;
  private final ReceiptProperties receiptProperties;

  public int forProduct(Product product) {
    return product.getCreatedVia() == ProductCreatedVia.RECEIPT
        ? receiptItemConfirmations()
        : catalogProperties.getDraftConfirmations();
  }

  public int forStore() {
    return catalogProperties.getDraftConfirmations();
  }

  /** 0 = zboží z účtenky se zakládá rovnou jako ACTIVE ({@link ProductCatalogService#receiptItem}). */
  public int receiptItemConfirmations() {
    return Math.max(0, receiptProperties.getItemConfirmations());
  }
}
