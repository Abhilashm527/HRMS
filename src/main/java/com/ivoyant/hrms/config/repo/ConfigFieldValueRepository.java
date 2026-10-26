package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigFieldValue;
import com.ivoyant.hrms.config.domain.ConfigFieldValueId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigFieldValueRepository extends JpaRepository<ConfigFieldValue, ConfigFieldValueId> {

	List<ConfigFieldValue> findByOrgIdAndAreaKeyAndRecordId(UUID orgId, String areaKey, UUID recordId);

	long countByFieldId(UUID fieldId);

	void deleteByOrgIdAndAreaKeyAndRecordId(UUID orgId, String areaKey, UUID recordId);
}
