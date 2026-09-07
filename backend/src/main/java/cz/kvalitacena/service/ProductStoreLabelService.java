package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductStoreLabelConfirmationRepository;
import cz.kvalitacena.db.repo.ProductStoreLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Učí mapování „jak to obchod tiskne" → katalogová položka, a to VÝHRADNĚ z řádku účtenky,
 * který skutečně vedl k zápisu ceny — tytéž podmínky jako u {@link ProductAliasService}.
 * Jinak by šlo mapovací tabulku zaplevelit nahráním libovolného obrázku (docs/rozvoj.md).
 *
 * <p>Rozsah je řetězec, když ho provozovna má (řetězec tiskne stejné zkratky ve všech
 * provozovnách), jinak samotná provozovna.
 */
@Service
@RequiredArgsConstructor
public class ProductStoreLabelService {

  /** Pod touhle délkou je to spíš zbytek rozsypaného OCR než označení zboží. */
  private static final int MIN_LABEL_LENGTH = 2;

  private final ProductStoreLabelRepository labelRepository;
  private final ProductStoreLabelConfirmationRepository confirmationRepository;
  private final CatalogProperties catalogProperties;

  @Transactional
  public void confirmFromObservation(Long productId, Store store, AppUser submitter,
      String rawLabel) {
    if (submitter == null || productId == null || rawLabel == null) {
      return;
    }
    String label = rawLabel.trim().replaceAll("\\s+", " ");
    if (label.length() < MIN_LABEL_LENGTH || label.length() > 200) {
      return;
    }
    Long chainId = store.getChain() == null ? null : store.getChain().getId();
    Long storeId = chainId == null ? store.getId() : null;

    // Jeden příkaz kvůli souběhu dvou prvních potvrzení — chycení unique výjimky uvnitř JPA
    // transakce by ji označilo rollback-only, stejný důvod jako v ProductAliasService.
    Object[] row = labelRepository
        .upsertReturningIdAndProduct(productId, label, chainId, storeId).getFirst();
    Long labelId = ((Number) row[0]).longValue();
    Long ownerProductId = ((Number) row[1]).longValue();
    // Unikát je na označení v rozsahu, ne na dvojici (označení, zboží). Když tutéž zkratku
    // v témže řetězci už drží jiná položka, NEPŘEPISUJE se ani nepotvrzuje — je to případ pro
    // moderaci (typicky duplicita ke sloučení), ne stav, který by měl rozhodnout poslední zápis.
    if (!ownerProductId.equals(productId)) {
      return;
    }
    confirmationRepository.insertIfAbsent(labelId, submitter.getId());
    labelRepository.activateIfConfirmed(labelId, catalogProperties.getLabelConfirmations());
  }
}
