package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A project people are allocated to across departments. {@code active} is the archive flag; {@code status} is its life cycle. */
@Entity
@Table(name = "project")
@Getter
@Setter
public class Project extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "code", nullable = false)
	private String code;

	@Column(name = "client")
	private String client = "Internal";

	@Column(name = "owner_employee_id")
	private UUID ownerEmployeeId;

	/** A {@link ProjectStatus} name. */
	@Column(name = "status", nullable = false)
	private String status = ProjectStatus.ACTIVE.name();

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Column(name = "department_id")
	private UUID departmentId;

	@Column(name = "billable", nullable = false)
	private boolean billable;

	@Column(name = "description")
	private String description;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
