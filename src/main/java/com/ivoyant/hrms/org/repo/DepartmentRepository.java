package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Department;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

	List<Department> findByOrgId(UUID orgId);

	List<Department> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

	Optional<Department> findByIdAndOrgId(UUID id, UUID orgId);

	List<Department> findByOrgIdAndParentId(UUID orgId, UUID parentId);

	boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);

	boolean existsByOrgIdAndNameIgnoreCaseAndIdNot(UUID orgId, String name, UUID id);

	boolean existsByOrgIdAndCodeIgnoreCase(UUID orgId, String code);

	boolean existsByOrgIdAndCodeIgnoreCaseAndIdNot(UUID orgId, String code, UUID id);
}
