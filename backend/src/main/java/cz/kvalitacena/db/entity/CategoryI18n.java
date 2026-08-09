package cz.kvalitacena.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Překlad {@link Category#getName()} do jiného jazyka než češtiny (docs/lokalizace.md) —
 * {@code core.category.name} zůstává zdrojová čeština a fallback, tahle tabulka nese jen
 * JINÉ jazyky (žádný řádek s locale="cs"). Lokalizovaný název se skládá až při čtení
 * (viz {@code ProductGraphQlController} `@BatchMapping(typeName = "Category", field = "name")`),
 * ne přepisem entity — stejný princip jako u ostatního "čtení s překryvem" v projektu.
 */
@Entity
@Table(name = "category_i18n", schema = "core")
@IdClass(CategoryI18nId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryI18n {

  @Id
  @Column(name = "category_id")
  private Long categoryId;

  @Id
  @Column(name = "locale", length = 5)
  private String locale;

  @Column(name = "name", nullable = false, length = 120)
  private String name;
}
