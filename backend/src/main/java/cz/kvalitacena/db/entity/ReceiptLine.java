package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;

/**
 * Jeden řádek účtenky.
 *
 * <p>POZOR na nejpravděpodobnější tichou chybu celé funkce (docs/rozvoj.md): u váhového zboží
 * je {@code unitPrice} cena za kg/l (289,00 Kč/kg), NIKDY zaplacená částka za nakoupené
 * množství (87,30 Kč za 0,302 kg). {@code quantity} říká, kolik toho člověk koupil, a do ceny
 * nevstupuje — {@code net_content_base} na observaci je snapshot z katalogu, ne odsud.
 */
@Entity
@Table(name = "receipt_line", schema = "ai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptLine implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "receipt_id", nullable = false)
  private Long receiptId;

  @Column(name = "line_no", nullable = false)
  private int lineNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 10)
  private ReceiptLineKind kind;

  /** Označení tak, jak ho obchod vytiskl — podklad pro {@code core.product_store_label}. */
  @Column(name = "raw_label", nullable = false, length = 200)
  private String rawLabel;

  /** Vnitroobchodní kód, pokud ho účtenka tiskne — Albert ne, jiné řetězce ano. */
  @Column(name = "raw_code", length = 60)
  private String rawCode;

  @Column(name = "quantity", precision = 12, scale = 3)
  private BigDecimal quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "quantity_basis", length = 12)
  private QuantityBasis quantityBasis;

  @Column(name = "unit_price", precision = 12, scale = 2)
  private BigDecimal unitPrice;

  @Column(name = "line_total", precision = 12, scale = 2)
  private BigDecimal lineTotal;

  @Column(name = "vat_code", length = 2)
  private String vatCode;

  /** U slevy číslo řádku položky, ke které se váže. */
  @Column(name = "applies_to_line_no")
  private Integer appliesToLineNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "match_status", nullable = false, length = 12)
  @Builder.Default
  private ReceiptLineMatchStatus matchStatus = ReceiptLineMatchStatus.UNMATCHED;

  @Column(name = "matched_product_id")
  private Long matchedProductId;

  @Enumerated(EnumType.STRING)
  @Column(name = "match_source", length = 12)
  private ReceiptMatchSource matchSource;

  @Column(name = "match_score", precision = 4, scale = 3)
  private BigDecimal matchScore;

  @Column(name = "observation_id")
  private Long observationId;

  @Column(name = "note", length = 500)
  private String note;

  @Override
  public boolean isNew() {
    return id == null;
  }
}
