package com.ivoyant.hrms.org.dto;

/**
 * What still points at a department. {@code employees} are the people in it now, {@code employeesTotal} includes
 * sub-departments, {@code exitedEmployees} are former employees whose history points here.
 */
public record DepartmentUsage(long employees, long employeesTotal, long exitedEmployees, long children, long titles,
		long teams, long openJobs, long pendingHires, long roleScopes, long leaveCampaigns, long positions,
		long fieldValues, String message) {

	/** Anything that stops the department from being deleted. */
	public boolean blocksDelete() {
		return employees > 0 || exitedEmployees > 0 || children > 0 || titles > 0 || teams > 0 || openJobs > 0
				|| pendingHires > 0 || roleScopes > 0 || leaveCampaigns > 0 || positions > 0 || fieldValues > 0;
	}
}
