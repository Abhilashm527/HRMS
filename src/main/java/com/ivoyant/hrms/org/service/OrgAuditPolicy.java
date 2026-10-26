package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.AuditPolicy;
import com.ivoyant.hrms.org.OrgSettings;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns the two audit settings into a rule. "Log permission changes" off drops everything about roles and who holds
 * them. "Audit log" off drops changes to settings and to how roles and forms are defined, but keeps role
 * assignments and business data changes, which are security events.
 */
@Component
class OrgAuditPolicy implements AuditPolicy {

	private static final Set<String> PERMISSION = Set.of("Role", "RolePermissions", "UserRole", "AreaOwner", "Delegation");
	private static final Set<String> DEFINITIONS = Set.of("Role", "RolePermissions");

	private final OrgSettings settings;

	OrgAuditPolicy(OrgSettings settings) {
		this.settings = settings;
	}

	@Override
	public boolean record(String module, String entity) {
		if ("iam".equals(module) && PERMISSION.contains(entity) && !settings.logPermissionChanges()) {
			return false;
		}
		if (settings.auditLog()) {
			return true;
		}
		boolean settingsKind = "org".equals(module)
				|| ("config".equals(module) && !entity.startsWith("FieldValues") && !entity.startsWith("ChangeRequest"))
				|| ("iam".equals(module) && DEFINITIONS.contains(entity));
		return !settingsKind;
	}
}
