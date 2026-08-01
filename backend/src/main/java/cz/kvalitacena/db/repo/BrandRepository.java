package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {
}
