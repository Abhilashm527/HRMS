package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Designation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignationRepository extends JpaRepository<Designation, UUID> {

	List<Designation> findByOrgId(UUID orgId);

	List<Designation> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

	Optional<Designation> findByIdAndOrgId(UUID id, UUID orgId);

	List<Designation> findByOrgIdAndReportsToId(UUID orgId, UUID reportsToId);

	boolean existsByOrgIdAndTitleIgnoreCase(UUID orgId, String title);

	boolean existsByOrgIdAndTitleIgnoreCaseAndIdNot(UUID orgId, String title, UUID id);

	boolean existsByOrgIdAndCodeIgnoreCase(UUID orgId, String code);

	boolean existsByOrgIdAndCodeIgnoreCaseAndIdNot(UUID orgId, String code, UUID id);
}
