package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.RefreshToken;
import cz.kvalitacena.db.entity.RevokeReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

  /** Revokuje celou rodinu najednou — používá se při detekci reuse (viz RefreshTokenService). */
  @Modifying
  @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokeReason = :reason "
      + "WHERE t.familyUid = :familyUid AND t.revokedAt IS NULL")
  int revokeAllByFamilyUid(@Param("familyUid") UUID familyUid, @Param("reason") RevokeReason reason,
      @Param("now") OffsetDateTime now);

  @Modifying
  @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokeReason = :reason "
      + "WHERE t.user.id = :userId AND t.revokedAt IS NULL")
  int revokeAllByUserId(@Param("userId") Long userId, @Param("reason") RevokeReason reason,
      @Param("now") OffsetDateTime now);

  @Modifying
  @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before AND t.revokedAt IS NOT NULL")
  int deleteAllRevokedExpiredBefore(@Param("before") OffsetDateTime before);
}
