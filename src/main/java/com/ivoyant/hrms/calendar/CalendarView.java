package com.ivoyant.hrms.calendar;

import java.util.UUID;

/** A holiday calendar; {@code state} is null for the national-only calendar. */
public record CalendarView(UUID id, String name, String state, boolean active) {
}
