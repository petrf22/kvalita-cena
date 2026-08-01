package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  Optional<AppUser> findByEmailHash(byte[] emailHash);

  Optional<AppUser> findByPublicUid(UUID publicUid);

  boolean existsByPublicHandle(String publicHandle);
}
