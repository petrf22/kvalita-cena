package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ObservationStatus;
import cz.kvalitacena.db.entity.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

  List<PriceObservation> findByProductIdAndStoreIdAndStatus(Long productId, Long storeId, ObservationStatus status);
}
