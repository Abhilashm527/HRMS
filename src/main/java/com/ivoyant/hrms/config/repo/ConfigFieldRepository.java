package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigField;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigFieldRepository extends JpaRepository<ConfigField, UUID> {

	List<ConfigField> findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(UUID orgId, String areaKey);

	List<ConfigField> findByOrgId(UUID orgId);

	List<ConfigField> findByOrgIdAndSectionId(UUID orgId, UUID sectionId);

	Optional<ConfigField> findByIdAndOrgId(UUID id, UUID orgId);

	boolean existsByOrgIdAndAreaKeyAndFieldKey(UUID orgId, String areaKey, String fieldKey);
}
