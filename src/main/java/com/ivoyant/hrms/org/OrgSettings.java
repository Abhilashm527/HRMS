package com.ivoyant.hrms.org;

import java.time.Period;

/** The organization's 22 settings, for the modules that obey them; cached for a minute and evicted on change. */
public interface OrgSettings {

	// employee management
	boolean autoEmployeeId();

	String idFormat();

	boolean managerApprovesChanges();

	boolean multiTitle();

	boolean positionMandatory();

	String titleFormat();

	/** An offer above the position's band goes to approval (off: it only warns). */
	boolean outOfBandNeedsApproval();

	// departments
	boolean nestedDepartments();

	int maxDepartmentDepth();

	boolean departmentCodeRequired();

	int maxReportingDepth();

	/** More direct reports than this only earns a warning. */
	int maxSpan();

	/** The most people one mentor can mentor at once. */
	int maxMentees();

	// roles and permissions
	boolean customRoles();

	boolean roleInheritance();

	boolean sensitiveRoleSecondApprover();

	boolean logPermissionChanges();

	SalaryVisibility salaryVisibility();

	// reporting and analytics
	boolean orgChart();

	boolean analytics();

	Period retention();

	// security and compliance
	boolean auditLog();

	boolean twoFactor();

	PasswordPolicy passwordPolicy();

	boolean encryptSensitive();
}
