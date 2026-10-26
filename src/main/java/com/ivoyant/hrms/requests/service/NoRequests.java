package com.ivoyant.hrms.requests.service;

import com.ivoyant.hrms.requests.RequestTypeUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Placeholder until requests exist: no request uses any type. */
@Component
class NoRequests implements RequestTypeUsage {

	@Override
	public long requestsOfType(UUID typeId) {
		return 0;
	}
}
