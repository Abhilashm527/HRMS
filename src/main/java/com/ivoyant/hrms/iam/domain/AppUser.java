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
@Table(name = "app_user")
@Getter
@Setter
public class AppUser extends TenantScoped {

	@Column(name = "employee_id", nullable = false, updatable = false)
	private UUID employeeId;

	/** Entra object id. */
	@Column(name = "external_subject")
	private String externalSubject;

	@Column(name = "status", nullable = false)
	private String status = "ACTIVE";

	@Column(name = "last_login_at")
	private Instant lastLoginAt;
}
