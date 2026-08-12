package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
}
