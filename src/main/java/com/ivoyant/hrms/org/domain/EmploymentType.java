package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** How a person is employed: it sets probation, notice, and whether they are in leave policies and payroll. */
@Entity
@Table(name = "employment_type")
@Getter
@Setter
public class EmploymentType extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "code", nullable = false)
	private String code;

	@Column(name = "probation_months", nullable = false)
	private int probationMonths;

	@Column(name = "notice_days", nullable = false)
	private int noticeDays;

	@Column(name = "leave_eligible", nullable = false)
	private boolean leaveEligible;

	@Column(name = "payroll_eligible", nullable = false)
	private boolean payrollEligible;

	/** People of a fixed-term type need a contract end date. */
	@Column(name = "is_fixed_term", nullable = false)
	private boolean fixedTerm;

	@Column(name = "sort", nullable = false)
	private int sort;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
