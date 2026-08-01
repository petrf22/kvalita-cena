package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "category", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "fk_category_parent"))
  private Category parent;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "slug", nullable = false, unique = true, length = 140)
  private String slug;

  // Např. "potraviny/mlecne/maslo" — pro rychlé filtrování podle větve stromu.
  @Column(name = "path", nullable = false, length = 400)
  private String path;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private int sortOrder = 0;

  @Override
  public boolean isNew() {
    return id == null;
  }
}
