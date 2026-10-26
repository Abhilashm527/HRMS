package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.common.event.ReportsRehomedEvent;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** When someone leaves, their direct reports move to the leaver's own manager (the history says EXIT_REHOME). */
@Component
class ReportsRehomer {

	private static final Logger log = LoggerFactory.getLogger(ReportsRehomer.class);

	private final EmployeeRepository employees;
	private final EmployeeRecords records;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	ReportsRehomer(EmployeeRepository employees, EmployeeRecords records, Audit audit, ApplicationEventPublisher events) {
		this.employees = employees;
		this.records = records;
		this.audit = audit;
		this.events = events;
	}

	@EventListener
	void onExit(EmployeeExitedEvent event) {
		Employee leaver = employees.findById(event.employeeId()).orElse(null);
		if (leaver == null) {
			return;
		}
		List<Employee> reports = employees.findByManagerId(leaver.getId()).stream()
				.filter(e -> e.getStatus() != com.ivoyant.hrms.employee.domain.EmployeeStatus.EXITED).toList();
		if (reports.isEmpty()) {
			return;
		}
		UUID to = leaver.getManagerId();
		for (Employee e : reports) {
			records.setManagerUnchecked(e.getId(), to, "EXIT_REHOME", null);
		}
		List<UUID> ids = reports.stream().map(Employee::getId).toList();
		audit.publish("employee", "Employee", leaver.getId(), "REHOME", Map.of("reports", ids.size()), Map.of("toManager", String.valueOf(to)));
		events.publishEvent(new ReportsRehomedEvent(leaver.getId(), to, ids));
		log.info("{} reports of {} moved to {}", ids.size(), leaver.getDisplayName(), to);
	}
}
