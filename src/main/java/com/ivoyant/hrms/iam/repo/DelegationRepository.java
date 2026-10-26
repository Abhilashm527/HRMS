package com.ivoyant.hrms.iam.repo;

import com.ivoyant.hrms.iam.domain.Delegation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

	List<Delegation> findByOrgIdOrderByStartsAtDesc(UUID orgId);

	List<Delegation> findByOrgIdAndFromUserIdOrOrgIdAndToUserIdOrderByStartsAtDesc(UUID orgId1, UUID from, UUID orgId2,
			UUID to);

	Optional<Delegation> findByIdAndOrgId(UUID id, UUID orgId);

	List<Delegation> findByToUserIdAndModuleAndStartsAtLessThanEqualAndEndsAtGreaterThan(UUID toUserId, String module,
			Instant now1, Instant now2);
}
