package com.ivoyant.hrms.attendance.service;

import com.ivoyant.hrms.common.event.EmployeeLocationChangedEvent;
import com.ivoyant.hrms.common.event.LocationPolicyChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Stub: Attendance will act on these once its engine exists; for now it only records that it was told. */
@Component
class AttendanceLocationListener {

	private static final Logger log = LoggerFactory.getLogger(AttendanceLocationListener.class);

	@ApplicationModuleListener
	void on(LocationPolicyChangedEvent e) {
		log.info("Attendance: location {} policy changed {} (effective {})", e.id(), e.changed(), e.effectiveFrom());
	}

	@ApplicationModuleListener
	void on(EmployeeLocationChangedEvent e) {
		log.info("Attendance: employee {} moved from location {} to {} (effective {})", e.employeeId(), e.from(), e.to(),
				e.effectiveFrom());
	}
}
