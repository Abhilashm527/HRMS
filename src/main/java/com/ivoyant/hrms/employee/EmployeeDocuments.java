package com.ivoyant.hrms.employee;

import java.util.UUID;

/** How other modules put a document on an employee's file (onboarding copies the verified hire documents, payroll adds letters). */
public interface EmployeeDocuments {

	/** Adds an already verified document. {@code source} is ONBOARDING, PAYROLL or LETTER. */
	void addVerified(UUID employeeId, String name, String category, String fileName, String mime, byte[] content, String source);
}
