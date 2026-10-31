package com.ivoyant.hrms.leave;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** What attendance and payroll ask the leave module: who is on approved leave on a day. */
public interface LeaveDays {

	/** Employees on approved leave on the day, with the leave type's code and whether it is a half day. */
	Map<UUID, OnLeave> onLeave(LocalDate day);

	record OnLeave(String code, String name, boolean half) {
	}
}
