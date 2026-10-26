package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.domain.ConfigFieldAccessId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigFieldAccessRepository extends JpaRepository<ConfigFieldAccess, ConfigFieldAccessId> {

	List<ConfigFieldAccess> findByFieldIdIn(Collection<UUID> fieldIds);

	List<ConfigFieldAccess> findByFieldId(UUID fieldId);

	void deleteByFieldId(UUID fieldId);

	void deleteByFieldIdAndGeneratedRuleTrue(UUID fieldId);
}
