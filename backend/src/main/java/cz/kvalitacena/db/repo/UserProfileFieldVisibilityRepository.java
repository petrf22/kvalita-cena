package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.Audience;
import cz.kvalitacena.db.entity.ProfileField;
import cz.kvalitacena.db.entity.UserProfileFieldVisibility;
import cz.kvalitacena.db.entity.UserProfileFieldVisibilityId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserProfileFieldVisibilityRepository
    extends JpaRepository<UserProfileFieldVisibility, UserProfileFieldVisibilityId> {

  List<UserProfileFieldVisibility> findAllByUserId(Long userId);

  void deleteAllByUserId(Long userId);

  boolean existsByUserIdAndFieldAndAudience(Long userId, ProfileField field, Audience audience);
}
