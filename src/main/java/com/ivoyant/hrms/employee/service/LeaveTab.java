package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.leave.LeavePolicy;
import com.ivoyant.hrms.leave.LeaveTypeView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The Leave tab: the types this person is eligible for. Balances and requests fill in when the leave ledger exists. */
@Component
class LeaveTab {

	private final LeavePolicy policy;

	LeaveTab(LeavePolicy policy) {
		this.policy = policy;
	}

	Map<String, Object> forEmployee(UUID employeeId, int year, boolean canApply) {
		List<Map<String, Object>> balances = new ArrayList<>();
		for (LeaveTypeView t : policy.activeTypes()) {
			if (!policy.eligible(employeeId, t, LocalDate.now())) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", t.code());
			m.put("name", t.name());
			m.put("quota", t.quota());
			m.put("used", 0);
			m.put("available", t.quota());
			m.put("access", "VIEW");
			balances.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("year", year);
		out.put("balances", balances);
		out.put("requests", List.of());
		out.put("canApply", canApply);
		return out;
	}
}
