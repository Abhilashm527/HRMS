package com.ivoyant.hrms.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Base for every business table: carries {@code org_id} (single-tenant today, multi-tenant ready). */
@MappedSuperclass
@Getter
@Setter
public abstract class TenantScoped extends BaseEntity {

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;
}
