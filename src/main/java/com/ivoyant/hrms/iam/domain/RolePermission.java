package com.ivoyant.hrms.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionId.class)
@Getter
@Setter
@NoArgsConstructor
public class RolePermission {

	@Id
	@Column(name = "role_id")
	private UUID roleId;

	@Id
	@Column(name = "permission_id")
	private UUID permissionId;

	@Id
	@Enumerated(EnumType.STRING)
	@Column(name = "scope")
	private Scope scope;

	public RolePermission(UUID roleId, UUID permissionId, Scope scope) {
		this.roleId = roleId;
		this.permissionId = permissionId;
		this.scope = scope;
	}
}
