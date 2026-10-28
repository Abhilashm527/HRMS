package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.EmployeeCreatedEvent;
import com.ivoyant.hrms.iam.domain.AppUser;
import com.ivoyant.hrms.iam.domain.Scope;
import com.ivoyant.hrms.iam.domain.UserRole;
import com.ivoyant.hrms.iam.repo.AppUserRepository;
import com.ivoyant.hrms.iam.repo.RoleRepository;
import com.ivoyant.hrms.iam.repo.UserRoleRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Gives every new employee a user account and the default {@code employee} role (same transaction). */
@Component
class UserProvisioning {

	private final AppUserRepository users;
	private final RoleRepository roles;
	private final UserRoleRepository userRoles;
	private final FgaOutbox fga;

	private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

	UserProvisioning(AppUserRepository users, RoleRepository roles, UserRoleRepository userRoles, FgaOutbox fga, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.users = users;
		this.roles = roles;
		this.userRoles = userRoles;
		this.fga = fga;
	}

	@EventListener
	void on(EmployeeCreatedEvent event) {
		if (users.findByEmployeeId(event.employeeId()).isPresent()) {
			return;
		}
		AppUser user = new AppUser();
		user.setOrgId(event.orgId());
		user.setEmployeeId(event.employeeId());
		user.setEmail(jdbc.sql("select lower(work_email) from employee where id = :id").param("id", event.employeeId()).query(String.class).single());
		user.setStatus("INVITED");
		users.saveAndFlush(user);
		roles.findByOrgIdAndRoleKey(event.orgId(), "employee").ifPresent(role -> {
			UserRole ur = new UserRole();
			ur.setUserId(user.getId());
			ur.setRoleId(role.getId());
			ur.setScope(Scope.OWN);
			userRoles.save(ur);
			fga.write(FgaTuples.roleMember(event.employeeId(), role.getRoleKey()));
		});
	}
}
