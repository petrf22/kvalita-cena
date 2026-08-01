package cz.kvalitacena.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

@Entity
@Table(name = "retail_chain", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetailChain implements Persistable<Long> {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "slug", nullable = false, unique = true, length = 140)
  private String slug;

  @Enumerated(EnumType.STRING)
  @Column(name = "chain_type", nullable = false, length = 20)
  private ChainType chainType;

  @Column(name = "country", nullable = false, length = 2)
  @Builder.Default
  private String country = "CZ";

  @Column(name = "website", length = 300)
  private String website;

  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
  }

  @Override
  public boolean isNew() {
    return id == null;
  }
}
