package com.ivoyant.hrms.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "holiday")
@Getter
@Setter
public class Holiday {

	@Id
	private UUID id = UUID.randomUUID();

	@Column(name = "calendar_id", nullable = false)
	private UUID calendarId;

	@Column(name = "date", nullable = false)
	private LocalDate day;

	@Column(name = "name", nullable = false)
	private String name;

	/** The holiday type (National, Regional, Optional ...); whether it is a day off is the type's setting. */
	@Column(name = "type_id", nullable = false)
	private UUID typeId;
}
