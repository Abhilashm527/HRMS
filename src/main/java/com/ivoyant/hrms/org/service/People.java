package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Plain-SQL lookups of the few employee facts teams and projects need (org does not depend on the employee module). */
@Component
class People {

	/** {@code status} is the employee status name (ACTIVE, ON_NOTICE, EXITED ...). */
	record Emp(UUID id, String code, String name, String status, UUID departmentId) {

		boolean exited() {
			return "EXITED".equals(status);
		}

		boolean live() {
			return "ACTIVE".equals(status) || "ON_NOTICE".equals(status);
		}
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	People(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	Emp find(UUID id) {
		return id == null ? null : jdbc.sql("select id, employee_code, display_name, status, department_id from employee where id = :id and org_id = :org")
				.param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> new Emp(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getObject(5, UUID.class)))
				.optional().orElse(null);
	}

	Map<UUID, String> names(Collection<UUID> ids) {
		Map<UUID, String> out = new HashMap<>();
		if (ids.isEmpty()) {
			return out;
		}
		jdbc.sql("select id, display_name from employee where id in (:ids)").param("ids", ids).query((rs, i) -> {
			out.put(rs.getObject(1, UUID.class), rs.getString(2));
			return 0;
		}).list();
		return out;
	}

	static String initials(String name) {
		return Arrays.stream(name.trim().split("\\s+")).filter(p -> !p.isEmpty()).limit(2)
				.map(p -> p.substring(0, 1).toUpperCase(Locale.ROOT)).collect(Collectors.joining());
	}
}
