package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "department")
@Getter
@Setter
public class Department extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "code")
	private String code;

	@Column(name = "head_employee_id")
	private UUID headEmployeeId;

	@Column(name = "parent_id")
	private UUID parentId;

	@Column(name = "cost_centre")
	private String costCentre;

	@Column(name = "default_location_id")
	private UUID defaultLocationId;

	@Column(name = "description")
	private String description;

	/** Order among siblings in the tree (then by name). */
	@Column(name = "sort", nullable = false)
	private int sort;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
