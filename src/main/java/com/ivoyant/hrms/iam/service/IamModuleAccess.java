package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("moduleAccess")
class IamModuleAccess implements ModuleAccess {

	private final IamDirectory iam;
	private final CurrentUser currentUser;

	IamModuleAccess(IamDirectory iam, CurrentUser currentUser) {
		this.iam = iam;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean has(String module, String level) {
		UUID me = currentUser.employeeId();
		if (me == null) {
			return false;
		}
		PermLevel needed = PermLevel.valueOf(level.toUpperCase());
		Map<UUID, PermLevel> perms = iam.permissionLevels(module);
		for (RoleRef r : iam.effectiveRoles(me, module)) {
			if ("super_admin".equals(r.key()) || "hr_admin".equals(r.key())
					|| perms.getOrDefault(r.id(), PermLevel.NONE).atLeast(needed)) {
				return true;
			}
		}
		return false;
	}
}
