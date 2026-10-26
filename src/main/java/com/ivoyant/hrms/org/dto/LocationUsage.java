package com.ivoyant.hrms.org.dto;

/**
 * What still points at a location. {@code employees} counts every employee, exited ones included (payslips and
 * history point here); {@code exitedEmployees} is the exited part of that number.
 */
public record LocationUsage(long employees, long exitedEmployees, long departments, long assets,
		long openRequisitions, long pendingHires, long roleScopes, long fieldValues) {

	public boolean inUse() {
		return employees > 0 || departments > 0 || assets > 0 || openRequisitions > 0 || pendingHires > 0
				|| roleScopes > 0 || fieldValues > 0;
	}
}
