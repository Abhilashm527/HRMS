package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shapes the dotted-line and mentor API returns. */
public final class RelationDtos {

	private RelationDtos() {
	}

	public record Person(UUID id, String name, String title) {
	}

	/** {@code status} is SCHEDULED (not started), ACTIVE or ENDED. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Node(UUID id, String type, String typeLabel, Person employee, Person related, LocalDate fromDate,
			LocalDate toDate, String status, String note, String endReason, Long version,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

		public Node withWarnings(List<String> w) {
			return new Node(id, type, typeLabel, employee, related, fromDate, toDate, status, note, endReason, version, w);
		}
	}

	/** The four tiles of the screen. */
	public record Summary(long directLines, long dotted, long mentors, long ended) {
	}

	public record EmployeeRelations(List<Node> dotted, List<Node> mentors, List<Node> mentees, List<Node> dottedReports) {
	}
}
