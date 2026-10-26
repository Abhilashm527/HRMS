package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A functional team inside one department. The lead is the default manager suggested for new members. */
@Entity
@Table(name = "team")
@Getter
@Setter
public class Team extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "department_id")
	private UUID departmentId;

	@Column(name = "lead_employee_id")
	private UUID leadEmployeeId;

	@Column(name = "ms_channel_id")
	private String msChannelId;

	@Column(name = "description")
	private String description;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
