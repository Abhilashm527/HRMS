package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	List<AppUser> findByOrgId(UUID orgId);

	Optional<AppUser> findByIdAndOrgId(UUID id, UUID orgId);

	Optional<AppUser> findByEmployeeId(UUID employeeId);

	List<AppUser> findByIdIn(Collection<UUID> ids);
}
