package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	List<Team> findByOrgId(UUID orgId);

	Optional<Team> findByIdAndOrgId(UUID id, UUID orgId);
}
