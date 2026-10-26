package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	List<Project> findByOrgId(UUID orgId);

	Optional<Project> findByIdAndOrgId(UUID id, UUID orgId);
}
