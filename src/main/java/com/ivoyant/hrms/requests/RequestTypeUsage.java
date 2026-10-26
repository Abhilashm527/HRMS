package com.ivoyant.hrms.requests;

import java.util.UUID;

/** How many requests use a request type. The requests part of the app plugs in here; until it exists nothing is in use. */
public interface RequestTypeUsage {

	long requestsOfType(UUID typeId);
}
