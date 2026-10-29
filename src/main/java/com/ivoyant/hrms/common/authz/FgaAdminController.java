package com.ivoyant.hrms.common.authz;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ForbiddenException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/fga")
public class FgaAdminController {

	private final FgaSyncService sync;
	private final AuthzService authz;
	private final CurrentUser currentUser;
	private final JdbcClient jdbc;

	public FgaAdminController(FgaSyncService sync, AuthzService authz, CurrentUser currentUser, JdbcClient jdbc) {
		this.sync = sync;
		this.authz = authz;
		this.currentUser = currentUser;
		this.jdbc = jdbc;
	}

	/**
	 * Re-derives every OpenFGA tuple from the database (idempotent, additive). This is the repair for an OpenFGA that is
	 * empty or behind, so it can't depend only on OpenFGA: a super admin according to the database may run it too.
	 */
	@PostMapping("/sync")
	public Map<String, Integer> sync() {
		if (!superAdminInDatabase() && !authz.isSuperAdmin()) {
			throw new ForbiddenException("Only a super admin can sync permissions");
		}
		return Map.of("queued", sync.syncAll());
	}

	private boolean superAdminInDatabase() {
		UUID employee = currentUser.employeeId();
		if (employee == null) {
			return false;
		}
		return jdbc.sql("""
				select count(*) from user_role ur
				join app_user u on u.id = ur.user_id
				join role r on r.id = ur.role_id
				where u.employee_id = :e and u.org_id = :org and u.status = 'ACTIVE'
				  and r.org_id = :org and r.key = 'super_admin' and ur.scope = 'ORGANIZATION'
				""").param("e", employee).param("org", currentUser.orgId()).query(Long.class).single() > 0;
	}
}
