package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.event.DepartmentHeadChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stub: the automatic "Department head" role (held by anyone who heads at least one active department) is recomputed
 * here once that role exists. For now it only records that it was told.
 */
@Component
class DepartmentRoleListener {

	private static final Logger log = LoggerFactory.getLogger(DepartmentRoleListener.class);

	@ApplicationModuleListener
	void on(DepartmentHeadChangedEvent e) {
		log.info("Iam: head of department {} changed from {} to {}", e.id(), e.oldHeadId(), e.newHeadId());
	}
}
