package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.AreaOwnerRole;
import com.ivoyant.hrms.config.domain.AreaOwnerRoleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaOwnerRoleRepository extends JpaRepository<AreaOwnerRole, AreaOwnerRoleId> {

	List<AreaOwnerRole> findByOrgId(UUID orgId);

	List<AreaOwnerRole> findByOrgIdAndAreaKey(UUID orgId, String areaKey);

	void deleteByOrgIdAndAreaKey(UUID orgId, String areaKey);
}
