package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shapes the teams API returns. */
public final class TeamDtos {

	private TeamDtos() {
	}

	public record Department(UUID id, String name, String path) {
	}

	public record Person(UUID employeeId, String name, String initials) {
	}

	/** A team as the list and the drawer show it; parts that were not asked for are left out. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Node(UUID id, String name, Department department, Person lead, String description, Integer memberCount,
			boolean active, Long version, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

		public Node withWarnings(List<String> w) {
			return new Node(id, name, department, lead, description, memberCount, active, version, w);
		}
	}

	/** What every member may see of a team. */
	public record Basic(UUID id, String name, UUID departmentId, boolean active) {
	}

	/** For pickers: an active team with its department and lead. */
	public record Option(UUID id, String name, UUID departmentId, String departmentName, UUID leadEmployeeId) {
	}

	public record Member(UUID employeeId, String employeeCode, String name, boolean primary, boolean lead, LocalDate since,
			LocalDate until) {
	}

	public record Usage(long currentMembers, long pastMembers, long openJobs, long onboardingDrafts, long notificationAudiences,
			String message) {

		public boolean blocksDelete() {
			return currentMembers > 0 || pastMembers > 0 || openJobs > 0 || onboardingDrafts > 0 || notificationAudiences > 0;
		}
	}
}
