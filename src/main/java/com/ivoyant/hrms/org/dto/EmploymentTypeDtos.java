package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** The shapes the employment types API returns. */
public final class EmploymentTypeDtos {

	private EmploymentTypeDtos() {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Node(UUID id, String name, String code, int probationMonths, int noticeDays, boolean leaveEligible,
			boolean payrollEligible, boolean fixedTerm, int sort, boolean active, Integer headcount, Long version,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

		public Node withWarnings(List<String> w) {
			return new Node(id, name, code, probationMonths, noticeDays, leaveEligible, payrollEligible, fixedTerm, sort, active,
					headcount, version, w);
		}
	}

	/** What every member may see: enough to pick a type. */
	public record Option(UUID id, String name, String code, boolean fixedTerm, int probationMonths) {
	}

	public record Usage(long employees, long exitedEmployees, long historyRows, long accountRules, long inviteTemplates,
			String message) {

		public boolean blocksDelete() {
			return employees > 0 || exitedEmployees > 0 || historyRows > 0 || accountRules > 0 || inviteTemplates > 0;
		}
	}
}
