package com.ivoyant.hrms.onboarding.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A new hire row. {@code stages} is the six-step tracker (PROFILE, M365, INVITE, DETAILS_DOCS, HR_VERIFICATION, DAY_1). */
public record Hire(UUID id, UUID orgId, String first, String last, String personalEmail, String phone, String gender,
		LocalDate dob, String title, UUID titleId, UUID departmentId, UUID managerId, UUID locationId,
		UUID employmentTypeId, LocalDate doj, Integer probationMonths, BigDecimal ctcLakh, LocalDate contractEnd,
		String status, boolean[] stages, Instant detailsSubmittedAt, UUID employeeId, Instant joinedAt,
		Instant cancelledAt, String cancelReason, UUID inviteTemplateId, long version) {

	public String name() {
		return (first + " " + last).trim();
	}

	public boolean open() {
		return joinedAt == null && cancelledAt == null;
	}
}
