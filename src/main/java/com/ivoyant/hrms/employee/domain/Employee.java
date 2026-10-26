package com.ivoyant.hrms.employee.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "employee")
@Getter
@Setter
public class Employee extends TenantScoped {

	@Column(name = "employee_code", nullable = false)
	private String employeeCode;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "last_name", nullable = false)
	private String lastName;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "work_email", nullable = false)
	private String workEmail;

	@Column(name = "personal_email")
	private String personalEmail;

	@Column(name = "phone")
	private String phone;

	@Column(name = "department_id")
	private UUID departmentId;

	@Column(name = "designation_id")
	private UUID designationId;

	/** The primary job title; the title decides {@code designationId}. */
	@Column(name = "title_id")
	private UUID titleId;

	@Column(name = "location_id")
	private UUID locationId;

	@Column(name = "manager_id")
	private UUID managerId;

	/** The type's name (kept in step with {@link #employmentTypeId} for older readers). */
	@Column(name = "employment_type", nullable = false)
	private String employmentType;

	@Column(name = "employment_type_id")
	private UUID employmentTypeId;

	@Column(name = "probation_end_date")
	private LocalDate probationEndDate;

	@Column(name = "confirmed_on")
	private LocalDate confirmedOn;

	@Column(name = "contract_end_date")
	private LocalDate contractEndDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private EmployeeStatus status = EmployeeStatus.ACTIVE;

	@Column(name = "joining_date", nullable = false)
	private LocalDate joiningDate;

	@Column(name = "exit_date")
	private LocalDate exitDate;

	@Column(name = "photo_key")
	private String photoKey;

	@Column(name = "photo_version", nullable = false)
	private int photoVersion;

	@Column(name = "archived_at")
	private java.time.Instant archivedAt;

	/** The onboarding hire this employee came from (no foreign key: hires may be purged). */
	@Column(name = "hire_id")
	private UUID hireId;

	/** Values of admin-configured fields, keyed by field key. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "custom", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> custom = new HashMap<>();
}
