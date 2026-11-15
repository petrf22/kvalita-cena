package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RecomputeQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface RecomputeQueueRepository extends JpaRepository<RecomputeQueue, Long> {

  /** Nevyřízené položky, které ještě nevyčerpaly pokusy — vadná položka nesmí blokovat čelo fronty. */
  List<RecomputeQueue> findTop200ByProcessedAtIsNullAndAttemptsLessThanOrderByEnqueuedAtAsc(int maxAttempts);

  @Modifying
  @Query("update RecomputeQueue q set q.processedAt = :now where q.id in :ids")
  int markProcessed(@Param("ids") Collection<Long> ids, @Param("now") OffsetDateTime now);

  @Modifying
  @Query("update RecomputeQueue q set q.attempts = q.attempts + 1 where q.id in :ids")
  int incrementAttempts(@Param("ids") Collection<Long> ids);

  @Modifying
  @Query("delete from RecomputeQueue q where q.processedAt < :cutoff")
  int deleteProcessedBefore(@Param("cutoff") OffsetDateTime cutoff);

  long countByProcessedAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);
}
