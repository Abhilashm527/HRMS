package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

	List<UserRole> findByUserId(UUID userId);

	Optional<UserRole> findByIdAndUserId(UUID id, UUID userId);

	boolean existsByRoleId(UUID roleId);
}
