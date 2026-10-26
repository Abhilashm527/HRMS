package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shapes the projects and allocation APIs return. */
public final class ProjectDtos {

	private ProjectDtos() {
	}

	public record Person(UUID employeeId, String name) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Node(UUID id, String name, String code, String client, boolean billable, String status, String statusLabel,
			Person manager, LocalDate startDate, LocalDate endDate, UUID departmentId, String description,
			Integer memberCount, Integer allocatedPct, boolean active, Long version,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

		public Node withWarnings(List<String> w) {
			return new Node(id, name, code, client, billable, status, statusLabel, manager, startDate, endDate, departmentId,
					description, memberCount, allocatedPct, active, version, w);
		}
	}

	public record Basic(UUID id, String name, String code, String status, boolean active) {
	}

	public record Option(UUID id, String name, String code, String client, String status) {
	}

	public record Member(UUID employeeId, String employeeCode, String name, String role, int allocation, LocalDate since,
			LocalDate until, String note, boolean manager, int totalAllocation,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {
	}

	public record Usage(long currentMembers, long pastMembers, long timesheetEntries, long invoices, String message) {

		public boolean blocksDelete() {
			return currentMembers > 0 || pastMembers > 0 || timesheetEntries > 0 || invoices > 0;
		}
	}

	public record Summary(long total, Map<String, Long> byStatus, long archived, long fullyAllocated, long overAllocated,
			String text) {
	}

	public record AllocationRow(UUID employeeId, String employeeCode, String name, UUID departmentId, int total, String state) {
	}

	public record AllocationCheck(int total, boolean ok, String message) {
	}

	public record Groups(List<GroupTeam> teams, List<GroupProject> projects, int totalAllocation, Map<String, String> text) {
	}

	public record GroupTeam(UUID id, String name, boolean primary) {
	}

	public record GroupProject(UUID id, String name, String code, String role, int allocation) {
	}
}
