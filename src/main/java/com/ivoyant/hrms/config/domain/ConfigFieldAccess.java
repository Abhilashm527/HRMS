package com.ivoyant.hrms.config.domain;

import com.ivoyant.hrms.config.FieldAccess;
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
@Table(name = "config_field_access")
@IdClass(ConfigFieldAccessId.class)
@Getter
@Setter
@NoArgsConstructor
public class ConfigFieldAccess {

	@Id
	@Column(name = "field_id")
	private UUID fieldId;

	@Id
	@Column(name = "role_id")
	private UUID roleId;

	@Enumerated(EnumType.STRING)
	@Column(name = "access")
	private FieldAccess access;

	@Enumerated(EnumType.STRING)
	@Column(name = "own_access")
	private FieldAccess ownAccess;

	@Enumerated(EnumType.STRING)
	@Column(name = "others_access")
	private FieldAccess othersAccess;

	/** Written from a custom field's "Visible to" (not an explicit Field-security choice): rewritten whenever that setting changes. */
	@Column(name = "generated", nullable = false)
	private boolean generatedRule;

	public ConfigFieldAccess(UUID fieldId, UUID roleId, FieldAccess access, FieldAccess ownAccess,
			FieldAccess othersAccess) {
		this.fieldId = fieldId;
		this.roleId = roleId;
		this.access = access;
		this.ownAccess = ownAccess;
		this.othersAccess = othersAccess;
	}
}
