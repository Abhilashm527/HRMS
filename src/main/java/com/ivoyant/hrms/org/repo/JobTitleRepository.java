package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.JobTitle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobTitleRepository extends JpaRepository<JobTitle, UUID> {

	List<JobTitle> findByOrgId(UUID orgId);

	List<JobTitle> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

	Optional<JobTitle> findByIdAndOrgId(UUID id, UUID orgId);
}
