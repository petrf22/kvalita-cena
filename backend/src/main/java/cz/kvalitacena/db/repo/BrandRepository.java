package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

  Optional<Brand> findByNameIgnoreCase(String name);

  boolean existsBySlug(String slug);
}
