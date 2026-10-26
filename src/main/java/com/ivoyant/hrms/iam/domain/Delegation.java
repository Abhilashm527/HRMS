package com.ivoyant.hrms.iam.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "delegation")
@Getter
@Setter
public class Delegation extends TenantScoped {

	@Column(name = "from_user_id", nullable = false, updatable = false)
	private UUID fromUserId;

	@Column(name = "to_user_id", nullable = false, updatable = false)
	private UUID toUserId;

	@Column(name = "module", nullable = false)
	private String module;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "reason")
	private String reason;
}
