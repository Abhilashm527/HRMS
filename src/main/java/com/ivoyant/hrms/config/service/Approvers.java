package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Who may decide change requests: super admins, HR admins, and roles with Approve in the area's module. */
@Component
public class Approvers {

	private final IamDirectory iam;
	private final CurrentUser currentUser;

	public Approvers(IamDirectory iam, CurrentUser currentUser) {
		this.iam = iam;
		this.currentUser = currentUser;
	}

	public boolean canDecide(ConfigArea area) {
		return canDecide(area, currentUser.employeeId());
	}

	public boolean canDecide(ConfigArea area, UUID employeeId) {
		if (employeeId == null) {
			return false;
		}
		Set<RoleRef> roles = iam.effectiveRoles(employeeId, area.getPermModule());
		Map<UUID, PermLevel> perms = iam.permissionLevels(area.getPermModule());
		return roles.stream().anyMatch(r -> "super_admin".equals(r.key()) || "hr_admin".equals(r.key())
				|| perms.getOrDefault(r.id(), PermLevel.NONE).atLeast(PermLevel.APPROVE));
	}
}
