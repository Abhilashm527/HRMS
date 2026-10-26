package com.ivoyant.hrms.iam.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Who may configure which Fields &amp; forms area. */
@Entity
@Table(name = "area_owner")
@Getter
@Setter
public class AreaOwner extends TenantScoped {

	@Column(name = "area_key", nullable = false, updatable = false)
	private String areaKey;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;
}
