package com.ivoyant.hrms.leave.service;

import com.ivoyant.hrms.leave.LeaveTypeUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Placeholder until leave requests and balances exist: no leave type is in use. */
@Component
class NoLeaveRequests implements LeaveTypeUsage {

	@Override
	public boolean inUse(UUID typeId) {
		return false;
	}
}
