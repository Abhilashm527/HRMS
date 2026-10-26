package com.ivoyant.hrms.iam.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "role")
@Getter
@Setter
public class Role extends TenantScoped {

	// not called "key": that is an HQL keyword
	@Column(name = "key", nullable = false, updatable = false)
	private String roleKey;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "system", nullable = false, updatable = false)
	private boolean system;
}
