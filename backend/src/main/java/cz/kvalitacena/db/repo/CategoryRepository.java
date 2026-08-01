package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
