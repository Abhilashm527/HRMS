package com.ivoyant.hrms.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "permission")
@Getter
@Setter
public class Permission {

	@Id
	private UUID id = UUID.randomUUID();

	@Column(name = "module", nullable = false)
	private String module;

	@Column(name = "action", nullable = false)
	private String action;
}
