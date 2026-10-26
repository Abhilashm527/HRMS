package com.ivoyant.hrms.employee;

import java.time.LocalDate;

/**
 * The status the screens show. The database keeps ACTIVE / ON_NOTICE / EXITED; PROBATION is an active person whose
 * probation has an end date and is not confirmed yet (an overdue probation stays PROBATION: nothing confirms by itself).
 */
public final class EmployeeStatuses {

	private EmployeeStatuses() {
	}

	public record View(String status, String label, String kind) {
	}

	public static View of(String dbStatus, LocalDate probationEnd, LocalDate confirmedOn) {
		if ("EXITED".equals(dbStatus)) {
			return new View("EXITED", "exited", "neutral");
		}
		if ("ON_NOTICE".equals(dbStatus)) {
			return new View("NOTICE", "notice period", "degraded");
		}
		if (probationEnd != null && confirmedOn == null) {
			return new View("PROBATION", "probation", "pending");
		}
		return new View("ACTIVE", "active", "healthy");
	}
}
