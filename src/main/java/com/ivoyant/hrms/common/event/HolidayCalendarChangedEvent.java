package com.ivoyant.hrms.common.event;

/** Holiday types or holidays changed: working-day counts (leave, attendance) must be read again. */
public record HolidayCalendarChangedEvent(String reason) {
}
