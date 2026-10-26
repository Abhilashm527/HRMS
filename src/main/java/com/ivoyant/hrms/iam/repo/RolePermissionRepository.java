package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.RolePermission;
import com.ivoyant.hrms.iam.domain.RolePermissionId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

	List<RolePermission> findByRoleId(UUID roleId);

	List<RolePermission> findByPermissionIdIn(Collection<UUID> permissionIds);

	void deleteByRoleId(UUID roleId);
}
