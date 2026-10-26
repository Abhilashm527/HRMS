package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.onboarding.domain.Hire;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The people to tell about a hire: the owners of the onboarding area plus the hire's manager. */
@Component
class HrOwners {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	HrOwners(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	Set<UUID> owners() {
		return new LinkedHashSet<>(jdbc.sql("""
				select u.employee_id from area_owner o join app_user u on u.id = o.user_id
				where o.org_id = :org and o.area_key = 'onboarding'
				union
				select u.employee_id from area_owner_role r join user_role ur on ur.role_id = r.role_id join app_user u on u.id = ur.user_id
				where r.org_id = :org and r.area_key = 'onboarding'""").param("org", currentUser.orgId()).query(UUID.class).list());
	}

	Set<UUID> forHire(Hire h) {
		Set<UUID> out = owners();
		out.add(h.managerId());
		return out;
	}
}
