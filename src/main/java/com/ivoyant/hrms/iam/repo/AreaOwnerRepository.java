package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.AreaOwner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaOwnerRepository extends JpaRepository<AreaOwner, UUID> {

	List<AreaOwner> findByOrgId(UUID orgId);

	Optional<AreaOwner> findByIdAndOrgId(UUID id, UUID orgId);

	boolean existsByOrgIdAndAreaKeyAndUserId(UUID orgId, String areaKey, UUID userId);
}
