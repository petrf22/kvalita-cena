package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.StoreUserEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreUserEditRepository extends JpaRepository<StoreUserEdit, StoreUserEdit.Id> {

  Optional<StoreUserEdit> findByStoreIdAndUserId(Long storeId, Long userId);

  /** GDPR export (AccountService) — všechny vlastní úpravy bez ohledu na obchod. */
  List<StoreUserEdit> findByUserId(Long userId);

  /** Dávkové dotažení patchů pro seznam provozoven a JEDNOHO viewera (hledání, ne N+1). */
  List<StoreUserEdit> findByStoreIdInAndUserId(Collection<Long> storeIds, Long userId);
}
