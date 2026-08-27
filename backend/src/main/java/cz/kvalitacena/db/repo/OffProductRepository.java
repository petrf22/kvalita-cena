package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.OffProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OffProductRepository extends JpaRepository<OffProduct, String> {

  List<OffProduct> findByGtinIn(Collection<String> gtins);
}
