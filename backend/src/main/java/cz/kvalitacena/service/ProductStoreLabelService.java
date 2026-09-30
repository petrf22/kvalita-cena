package cz.kvalitacena.service;

import cz.kvalitacena.config.ReceiptProperties;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Učí „Názvy na účtenkách" (core.product_store_label, docs/rozvoj.md, „Mapování obchodního
 * označení zboží na katalogovou položku") — vzorem {@link ProductAliasService}: vzniká jen
 * z řádku účtenky, který potvrdil přihlášený člověk, jeden účet jeden hlas, aktivace po
 * {@code app.receipt.label-confirmations}.
 *
 * <p>Směr je opačný než u aliasu: označení → zboží. Když už v rozsahu označení míří na JINÉ
 * zboží, nový hlas se nezapočítá — dvě položky hlásící se ke stejné zkratce v jednom řetězci
 * nejsou legitimní stav, ale případ pro moderaci (typicky duplicita ke sloučení).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStoreLabelService {

  private final ProductStoreLabelRepository labelRepository;
  private final ReceiptLineRepository receiptLineRepository;
  private final ReceiptProperties receiptProperties;
  private final EntityManager entityManager;

  @Transactional
  public void learnFromLine(Product product, Store store, Long userId, String rawLabel) {
    if (userId == null || rawLabel == null) {
      return;
    }
    String label = rawLabel.trim().replaceAll("\\s+", " ");
    if (label.length() < 2 || label.length() > 200) {
      return;
    }
    Long chainId = store.getChain() != null ? store.getChain().getId() : null;
    Long storeId = chainId == null ? store.getId() : null;

    // Jeden příkaz odolný proti souběhu, stejně jako u aliasu — chycená unique výjimka by
    // transakci označila rollback-only. DO UPDATE jen posune last_seen_at (označení je živé).
    Object[] row = (Object[]) entityManager.createNativeQuery("""
        INSERT INTO core.product_store_label(product_id, label, chain_id, store_id)
        VALUES (:productId, :label, :chainId, :storeId)
        ON CONFLICT (label_norm, COALESCE(chain_id, 0), COALESCE(store_id, 0))
        DO UPDATE SET last_seen_at = CURRENT_TIMESTAMP
        RETURNING id, product_id, label_norm
        """)
        .setParameter("productId", product.getId())
        .setParameter("label", label)
        .setParameter("chainId", chainId)
        .setParameter("storeId", storeId)
        .getSingleResult();
    long labelId = ((Number) row[0]).longValue();
    long mappedProductId = ((Number) row[1]).longValue();
    String labelNorm = (String) row[2];
    if (mappedProductId != product.getId()) {
      log.info("Označení „{}“ v rozsahu chain={} store={} už míří na zboží {}, uživatel páruje na {} — hlas se nepočítá",
          label, chainId, storeId, mappedProductId, product.getId());
      return;
    }

    labelRepository.confirm(labelId, userId);
    if (labelRepository.activateIfConfirmed(labelId, receiptProperties.getLabelConfirmations()) > 0) {
      // Pozdější párování (docs/rozvoj.md): nespárované řádky ostatních účtenek se shodným
      // označením se přepnou na návrh — cenu za vlastníka nezapisuje, potvrdí ji sám.
      int suggested = receiptLineRepository.suggestUnmatched(labelNorm, product.getId(), chainId, storeId,
          OffsetDateTime.now().minusDays(receiptProperties.getRematchMaxAgeDays()));
      log.info("Označení „{}“ aktivováno pro zboží {}, {} nespárovaných řádků dostalo návrh",
          label, product.getId(), suggested);
    }
  }
}
