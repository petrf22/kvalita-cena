package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReceiptLineRepository extends JpaRepository<ReceiptLine, Long> {

  List<ReceiptLine> findByReceiptIdOrderByLineNoAsc(Long receiptId);

  List<ReceiptLine> findByReceiptIdInOrderByReceiptIdAscLineNoAsc(Collection<Long> receiptIds);
}
