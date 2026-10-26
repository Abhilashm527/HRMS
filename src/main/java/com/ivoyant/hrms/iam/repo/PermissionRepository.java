package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.Permission;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

	Optional<Permission> findByModuleAndAction(String module, String action);

	List<Permission> findByModule(String module);

	List<Permission> findByIdIn(Collection<UUID> ids);
}
