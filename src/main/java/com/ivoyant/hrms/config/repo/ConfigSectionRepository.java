package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigSection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigSectionRepository extends JpaRepository<ConfigSection, UUID> {

	List<ConfigSection> findByOrgIdAndAreaKeyOrderBySortAscNameAsc(UUID orgId, String areaKey);

	List<ConfigSection> findByOrgId(UUID orgId);

	Optional<ConfigSection> findByIdAndOrgId(UUID id, UUID orgId);

	boolean existsByOrgIdAndAreaKeyAndSectionKey(UUID orgId, String areaKey, String sectionKey);
}
