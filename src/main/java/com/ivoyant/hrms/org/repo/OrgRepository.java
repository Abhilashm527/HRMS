package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.Org;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgRepository extends JpaRepository<Org, UUID> {

	boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
