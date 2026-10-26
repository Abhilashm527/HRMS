package com.ivoyant.hrms.calendar;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One holiday with its type. {@code dayOff} comes from the type: a working-day type (such as Optional) is shown to employees but still
 * counts as a working day. {@code inherited} marks a national holiday that a regional calendar picks up.
 */
public record HolidayView(LocalDate date, String name, UUID typeId, String type, String fg, String bg, boolean dayOff, boolean inherited) {
}
