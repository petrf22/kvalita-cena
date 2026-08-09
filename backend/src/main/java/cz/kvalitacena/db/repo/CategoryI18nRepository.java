package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.CategoryI18n;
import cz.kvalitacena.db.entity.CategoryI18nId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CategoryI18nRepository extends JpaRepository<CategoryI18n, CategoryI18nId> {

  /** Dávkově pro `@BatchMapping(typeName = "Category", field = "name")` — žádné N+1. */
  List<CategoryI18n> findByCategoryIdInAndLocale(Collection<Long> categoryIds, String locale);
}
