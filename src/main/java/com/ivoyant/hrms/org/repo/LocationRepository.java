package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Location;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

	List<Location> findByOrgId(UUID orgId);

	List<Location> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

	Optional<Location> findByIdAndOrgId(UUID id, UUID orgId);

	boolean existsByOrgIdAndNameIgnoreCase(UUID orgId, String name);

	boolean existsByOrgIdAndNameIgnoreCaseAndIdNot(UUID orgId, String name, UUID id);

	boolean existsByOrgIdAndCodeIgnoreCase(UUID orgId, String code);

	boolean existsByOrgIdAndCodeIgnoreCaseAndIdNot(UUID orgId, String code, UUID id);

	List<Location> findByOrgIdAndAssetStoreTrueAndActiveTrue(UUID orgId);
}
