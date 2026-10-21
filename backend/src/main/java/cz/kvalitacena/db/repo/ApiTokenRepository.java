package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

  Optional<ApiToken> findByTokenHashAndRevokedAtIsNull(byte[] tokenHash);

  @Transactional
  @Modifying
  @Query(value = "UPDATE auth.api_token SET last_used_at = CURRENT_TIMESTAMP WHERE id = :id", nativeQuery = true)
  void touch(@Param("id") Long id);
}
