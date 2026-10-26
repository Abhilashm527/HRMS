package com.ivoyant.hrms.leave;

import java.util.UUID;

/**
 * Is a leave type in use (any request or balance row)? The leave requests and ledger plug in here; until they exist nothing is in use, so a
 * type's code can still change and a custom type can be deleted.
 */
public interface LeaveTypeUsage {

	boolean inUse(UUID typeId);
}
