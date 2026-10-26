package com.ivoyant.hrms.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A role whose holders may configure an area. */
@Entity
@Table(name = "area_owner_role")
@IdClass(AreaOwnerRoleId.class)
@Getter
@Setter
@NoArgsConstructor
public class AreaOwnerRole {

	@Id
	@Column(name = "org_id")
	private UUID orgId;

	@Id
	@Column(name = "area_key")
	private String areaKey;

	@Id
	@Column(name = "role_id")
	private UUID roleId;

	public AreaOwnerRole(UUID orgId, String areaKey, UUID roleId) {
		this.orgId = orgId;
		this.areaKey = areaKey;
		this.roleId = roleId;
	}
}
