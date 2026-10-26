package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigChangeRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigChangeRequestRepository extends JpaRepository<ConfigChangeRequest, UUID> {

	Optional<ConfigChangeRequest> findByIdAndOrgId(UUID id, UUID orgId);

	List<ConfigChangeRequest> findByOrgIdAndStatusOrderByRequestedAtDesc(UUID orgId, String status);

	List<ConfigChangeRequest> findByOrgIdAndAreaKeyAndRecordIdAndStatus(UUID orgId, String areaKey, UUID recordId,
			String status);

	Optional<ConfigChangeRequest> findByOrgIdAndAreaKeyAndRecordIdAndFieldIdAndStatus(UUID orgId, String areaKey,
			UUID recordId, UUID fieldId, String status);

	long countByFieldIdAndStatus(UUID fieldId, String status);

	void deleteByOrgIdAndAreaKeyAndRecordId(UUID orgId, String areaKey, UUID recordId);
}
