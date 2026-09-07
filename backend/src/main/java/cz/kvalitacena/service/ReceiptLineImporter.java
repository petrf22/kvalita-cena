package cz.kvalitacena.service;

import cz.kvalitacena.controller.ObservationPriceInput;
import cz.kvalitacena.controller.ReceiptLineResult;
import cz.kvalitacena.controller.SubmitObservationsInput;
import cz.kvalitacena.db.entity.*;
import cz.kvalitacena.db.repo.ReceiptLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Zápis JEDNOHO řádku účtenky jako cenového záznamu — vlastní bean právě kvůli
 * {@link Propagation#REQUIRES_NEW}.
 *
 * <p>Bez toho by celý import spadl na první duplicitě: {@code PriceObservationService.submit}
 * je {@code @Transactional}, takže výjimka uvnitř označí SPOLEČNOU transakci rollback-only
 * a odchycení v cyklu by nepomohlo — commit by na konci stejně skončil
 * {@code UnexpectedRollbackException}. Každý řádek proto commituje sám za sebe; přeskočený
 * řádek nebrání zbytku účtenky (docs/rozvoj.md — „import duplicitní řádky přeskočí a nahlásí").
 *
 * <p>Ze stejného důvodu se tu výjimka ZÁMĚRNĚ nechytá: odchycení uvnitř transakční metody
 * transakci neodznačí, takže by její vlastní commit spadl na {@code UnexpectedRollbackException}.
 * Chytá ji až {@link ReceiptConfirmService}, tedy VNĚ hranice {@code REQUIRES_NEW}.
 */
@Service
@RequiredArgsConstructor
public class ReceiptLineImporter {

  private final PriceObservationService priceObservationService;
  private final ProductStoreLabelService productStoreLabelService;
  private final ReceiptLineRepository receiptLineRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReceiptLineResult importLine(Receipt receipt, ReceiptLine line, Store store,
      AppUser uploader) {
    SubmitObservationsInput input = new SubmitObservationsInput(
        line.getMatchedProductId(),
        store.getId(),
        line.getQuantityBasis(),
        receipt.getPurchasedAt(),
        receipt.getCurrency(),
        // Alias názvu se z účtenky neučí — zkratka „ROHLIK TUZ 43G" je interní označení
        // řetězce, ne název, který by kdokoli psal do hledání (docs/rozvoj.md). Učí se
        // mapování v core.product_store_label, ne core.product_alias.
        null,
        // Účtenka nese cenu, kterou zákazník zaplatil. Sleva je na ní vlastní řádek pod
        // položkou a jestli šlo o akci, klubovou cenu nebo množstevní slevu, z ní spolehlivě
        // poznat nejde — proto REGULAR za nediskontovanou jednotkovou cenu a poznámka
        // k ruční revizi (viz ReceiptConfirmService).
        List.of(new ObservationPriceInput(PriceKind.REGULAR, line.getUnitPrice(),
            null, null, null, null)));

    PriceObservation observation = priceObservationService
        .submit(input, uploader.getPublicUid(), ObservationSource.IMPORT, evidenceKind(receipt))
        .getFirst();
    line.setObservationId(observation.getId());
    line.setMatchStatus(ReceiptLineMatchStatus.IMPORTED);
    receiptLineRepository.save(line);
    // Mapování se učí VÝHRADNĚ z řádku, který skutečně vedl k ceně — tytéž podmínky jako
    // u aliasu. Jinak by šlo tabulku zaplevelit nahráním libovolného obrázku.
    productStoreLabelService.confirmFromObservation(line.getMatchedProductId(), store, uploader,
        line.getRawLabel());
    return new ReceiptLineResult(line.getId(), line.getLineNo(), line.getRawLabel(),
        "WRITTEN", observation.getId(), null);
  }

  /**
   * Druh důkazu určuje SERVER podle artefaktu, nikdy klient (docs/rozvoj.md) — sebedeklarovaný
   * důkaz by byl reputační útok, protože {@code f_evid} je násobič váhy.
   *
   * <p>Ve fázi lokálního OCR je artefaktem uložená účtenka, jejíž součet položek sedí na
   * vytištěnou částku. Účtenka, která nesedí, se do potvrzení vůbec nedostane
   * ({@link ReceiptConfirmService}), takže {@code NONE} je tu pojistka, ne běžná větev.
   */
  private static EvidenceKind evidenceKind(Receipt receipt) {
    return receipt.isTotalMatches() ? EvidenceKind.RECEIPT_OCR : EvidenceKind.NONE;
  }
}
