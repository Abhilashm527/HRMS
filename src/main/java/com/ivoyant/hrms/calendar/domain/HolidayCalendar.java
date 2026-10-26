package com.ivoyant.hrms.calendar.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "holiday_calendar")
@Getter
@Setter
public class HolidayCalendar extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	/** Null for the national-only calendar. */
	@Column(name = "state")
	private String state;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
