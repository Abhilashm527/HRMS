package com.ivoyant.hrms.config.repo;

import com.ivoyant.hrms.config.domain.ConfigRecordOwner;
import com.ivoyant.hrms.config.domain.ConfigRecordOwnerId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigRecordOwnerRepository extends JpaRepository<ConfigRecordOwner, ConfigRecordOwnerId> {
}
