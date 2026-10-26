package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.DelegationChangedEvent;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Leave delegations become OpenFGA {@code delegate} tuples (with the {@code active_window} condition) on every
 * direct report of the delegator, so {@code can_approve_leave} works for the delegate only inside the window.
 */
@Component
class DelegationFgaListener {

	private final EmployeeRepository employees;
	private final FgaOutbox fga;

	DelegationFgaListener(EmployeeRepository employees, FgaOutbox fga) {
		this.employees = employees;
		this.fga = fga;
	}

	@EventListener
	void on(DelegationChangedEvent event) {
		if (!"leave".equals(event.module())) {
			return;
		}
		for (Employee report : employees.findByManagerId(event.fromEmployeeId())) {
			var tuple = FgaTuples.delegate(event.toEmployeeId(), report.getId(), event.startsAt(), event.endsAt());
			if (event.removed()) {
				fga.delete(tuple);
			}
			else {
				fga.write(tuple);
			}
		}
	}
}
