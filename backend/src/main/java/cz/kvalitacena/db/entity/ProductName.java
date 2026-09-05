package cz.kvalitacena.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Komunitní název zboží v JINÉM jazyce, než je {@link Product#getNameLang()}
 * (docs/lokalizace.md) — {@code core.product.name} zůstává primární název a fallback, tahle
 * tabulka nese překlady. Vzor je {@link CategoryI18n}, jen s autorstvím: název sem zapisuje
 * uživatel, ne seed.
 *
 * <p>Řádek pro primární jazyk tu NIKDY není — jinak by měl název dva zdroje pravdy a oprava
 * jednoho by tiše nechala druhý.
 *
 * <p>Na rozdíl od {@link ProductUserEdit} je tohle GLOBÁLNÍ vrstva: doplnění jazyka, který
 * zboží ještě nemá, není nesouhlas s existující hodnotou, ale zaplnění díry — a jako takové
 * má cenu pro všechny (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty").
 * {@code createdByUserId} je jen stopa autorství pro budoucí konsolidaci; smazání účtu ho
 * vynuluje, ale název nechá být.
 */
@Entity
@Table(name = "product_name", schema = "core")
@IdClass(ProductNameId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductName {

  @Id
  @Column(name = "product_id")
  private Long productId;

  @Id
  @Column(name = "lang", length = 5)
  private String lang;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "created_by_user_id")
  private Long createdByUserId;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
