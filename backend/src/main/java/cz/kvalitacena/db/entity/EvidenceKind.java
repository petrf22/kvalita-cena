package cz.kvalitacena.db.entity;

/**
 * Na čem cena stojí — druhá osa vedle {@link ObservationSource} (docs/rozvoj.md, „Zdroj ceny:
 * kanál klienta vs. druh důkazu"). {@code source} říká, KDO záznam poslal, tenhle enum, ČÍM je
 * podložený; „z mobilu" a „z účtenky" nejsou alternativy, účtenka vyfocená telefonem je obojí.
 *
 * <p>Hodnotu určuje VÝHRADNĚ server podle skutečně připojeného artefaktu, nikdy klient v inputu:
 * důkaz je násobič váhy ({@code f_evid} = 1,30 účtenka+OCR / 1,15 foto cedulky / 1,00 bez důkazu,
 * docs/reputace.md), takže sebedeklarace by byla reputační útok, ne zobrazovací údaj.
 *
 * <p>{@code f_evid} zatím není v {@code PriceAggregationService.weightFor()} implementovaný —
 * sloupec existuje od začátku záměrně, aby se rozlišení nedopisovalo pozdější migrací
 * (docs/ai.md, „Vazba na f_evid"). {@code LEFLET} tu schválně není: cena z letáku je oznámení
 * budoucí platnosti, tedy jiný typ záznamu, ne jiný důkaz — viz docs/rozvoj.md.
 */
public enum EvidenceKind {
  NONE,
  PRICE_TAG_PHOTO,
  RECEIPT_OCR
}
