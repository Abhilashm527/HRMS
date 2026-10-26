package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.OrgSettingsRow;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgSettingsRepository extends JpaRepository<OrgSettingsRow, UUID> {
}
