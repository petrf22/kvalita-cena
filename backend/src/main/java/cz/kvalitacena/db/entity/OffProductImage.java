package cz.kvalitacena.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Jedna vybraná fotka z Open Food Facts pro konkrétní druh a jazyk obalu
 * ({@code selected_images.<kind>.{display,small}.<lang>}). Ukládá se VÝHRADNĚ URL, nikdy
 * binárka — snapshot cizích dat zůstává odkazem (docs/datovy-model.md, "Oddělení schémat
 * kvůli ODbL").
 *
 * <p>{@code @Embeddable} v {@link OffProduct#getImages()}, ne samostatná entita: celý snapshot
 * se při obnově přepisuje najednou ({@code OpenFoodFactsService.found}), takže delete+insert
 * celé kolekce je přesně to chování, které chceme.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class OffProductImage {

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private OffImageKind kind;

  @Column(name = "lang", nullable = false, length = 5)
  private String lang;

  @Column(name = "url", nullable = false, length = 1000)
  private String url;

  @Column(name = "small_url", length = 1000)
  private String smallUrl;
}
