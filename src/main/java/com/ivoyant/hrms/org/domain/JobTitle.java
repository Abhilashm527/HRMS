package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A job title: what a person is called. It belongs to one position (which sets level and band) and may be scoped to a department. */
@Entity
@Table(name = "job_title")
@Getter
@Setter
public class JobTitle extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "code")
	private String code;

	@Column(name = "designation_id")
	private UUID designationId;

	/** Blank means every department; otherwise that department and its sub-departments. */
	@Column(name = "department_id")
	private UUID departmentId;

	@Column(name = "description")
	private String description;

	@Column(name = "sort", nullable = false)
	private int sort;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
