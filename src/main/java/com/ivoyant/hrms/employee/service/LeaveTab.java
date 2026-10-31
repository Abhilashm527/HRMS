package com.ivoyant.hrms.employee.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.ivoyant.hrms.leave.LeaveSummary;

/** The Leave tab: the year's balances and requests, from the leave module. */
@Component
class LeaveTab {

	private final LeaveSummary leave;

	LeaveTab(LeaveSummary leave) {
		this.leave = leave;
	}

	Map<String, Object> forEmployee(UUID employeeId, int year, boolean canApply) {
		return leave.forEmployee(employeeId, year, canApply);
	}
}
