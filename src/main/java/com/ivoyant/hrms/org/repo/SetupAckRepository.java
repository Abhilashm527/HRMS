package com.ivoyant.hrms.org.repo;

import com.ivoyant.hrms.org.domain.SetupAck;
import com.ivoyant.hrms.org.domain.SetupAckId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupAckRepository extends JpaRepository<SetupAck, SetupAckId> {

	List<SetupAck> findByOrgId(UUID orgId);
}
