package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

	List<Role> findByOrgId(UUID orgId, Sort sort);

	Optional<Role> findByIdAndOrgId(UUID id, UUID orgId);

	Optional<Role> findByOrgIdAndRoleKey(UUID orgId, String roleKey);

	boolean existsByOrgIdAndRoleKey(UUID orgId, String roleKey);

	List<Role> findByIdIn(Collection<UUID> ids);
}
