package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.LoginChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface LoginChallengeRepository extends JpaRepository<LoginChallenge, Long> {

  Optional<LoginChallenge> findByChallengeUidAndConsumedAtIsNull(UUID challengeUid);

  /**
   * Zneplatní všechny dosud nepoužité výzvy pro tento e-mail — na e-mail smí být aktivní
   * nejvýš jedna výzva, jinak by šlo nafarmit desítky pokusů uhodnout kód (docs/soukromi.md).
   */
  @Modifying
  @Query("UPDATE LoginChallenge c SET c.consumedAt = :now "
      + "WHERE c.emailHash = :emailHash AND c.consumedAt IS NULL")
  int invalidateActiveChallenges(@Param("emailHash") byte[] emailHash, @Param("now") OffsetDateTime now);

  /**
   * Atomický pokus o inkrement — vrátí 0, pokud už byl vyčerpán max počet pokusů (souběh
   * dvou požadavků nesmí dovolit víc pokusů, než kolik povoluje max_attempts).
   */
  @Modifying
  @Query("UPDATE LoginChallenge c SET c.attempts = c.attempts + 1 "
      + "WHERE c.id = :id AND c.attempts < c.maxAttempts")
  int incrementAttempts(@Param("id") Long id);

  @Modifying
  @Query("DELETE FROM LoginChallenge c WHERE c.expiresAt < :before")
  int deleteAllByExpiresAtBefore(@Param("before") OffsetDateTime before);
}
