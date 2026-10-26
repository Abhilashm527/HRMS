package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigList;
import com.ivoyant.hrms.config.domain.ConfigListId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigListRepository extends JpaRepository<ConfigList, ConfigListId> {

	List<ConfigList> findByOrgIdAndModule(UUID orgId, String module);
}
