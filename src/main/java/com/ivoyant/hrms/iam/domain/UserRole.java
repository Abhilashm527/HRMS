package com.ivoyant.hrms.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_role")
@Getter
@Setter
public class UserRole {

	@Id
	private UUID id = UUID.randomUUID();

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "role_id", nullable = false, updatable = false)
	private UUID roleId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false)
	private Scope scope = Scope.ORGANIZATION;

	@Column(name = "scope_ref")
	private UUID scopeRef;
}
