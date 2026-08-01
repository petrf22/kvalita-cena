package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "product_code", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductCode implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_product_code_product"))
  private Product product;

  // EAN normalizovaný na GTIN-14 u code_type = GTIN (docs/datovy-model.md).
  @Column(name = "code", nullable = false, length = 20)
  private String code;

  // STORE_INTERNAL kódy nesou v sobě cenu a platí jen v rámci chain_id — NIKDY globální
  // identifikátor napříč řetězci.
  @Enumerated(EnumType.STRING)
  @Column(name = "code_type", nullable = false, length = 20)
  private CodeType codeType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "chain_id", foreignKey = @ForeignKey(name = "fk_product_code_chain"))
  private RetailChain chain;

  @Column(name = "is_primary", nullable = false)
  @Builder.Default
  private boolean primary = true;

  @Override
  public boolean isNew() {
    return id == null;
  }
}
