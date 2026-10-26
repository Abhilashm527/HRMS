package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.DepartmentHeadChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** When an employee exits, the departments they headed lose their head, and HR is told which ones. */
@Component
class DepartmentHeadListener {

	private static final Logger log = LoggerFactory.getLogger(DepartmentHeadListener.class);

	private final DepartmentRepository repo;
	private final FgaOutbox fga;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	DepartmentHeadListener(DepartmentRepository repo, FgaOutbox fga, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.fga = fga;
		this.audit = audit;
		this.events = events;
	}

	@EventListener
	void on(EmployeeExitedEvent e) {
		for (Department d : repo.findAll().stream().filter(x -> e.employeeId().equals(x.getHeadEmployeeId())).toList()) {
			d.setHeadEmployeeId(null);
			repo.saveAndFlush(d);
			fga.delete(FgaTuples.departmentHead(d.getId(), e.employeeId()));
			audit.publish("org", "Department", d.getId(), "HEAD_CLEARED", Map.of("head", e.employeeId().toString()), null);
			events.publishEvent(new DepartmentHeadChangedEvent(d.getId(), e.employeeId(), null));
			log.warn("{} has no head: {} left the organization", d.getName(), e.employeeId());
		}
	}
}
