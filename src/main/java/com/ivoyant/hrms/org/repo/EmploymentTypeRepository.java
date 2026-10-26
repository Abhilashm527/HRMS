package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.EmploymentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmploymentTypeRepository extends JpaRepository<EmploymentType, UUID> {

	List<EmploymentType> findByOrgId(UUID orgId);

	Optional<EmploymentType> findByIdAndOrgId(UUID id, UUID orgId);
}
