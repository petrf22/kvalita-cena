package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ReceiptImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReceiptImageRepository extends JpaRepository<ReceiptImage, Long> {

  List<ReceiptImage> findByReceiptIdOrderBySeqAsc(Long receiptId);

  List<ReceiptImage> findByReceiptIdInOrderBySeqAsc(Collection<Long> receiptIds);

  Optional<ReceiptImage> findByReceiptIdAndSeq(Long receiptId, Short seq);

  void deleteByReceiptId(Long receiptId);
}
