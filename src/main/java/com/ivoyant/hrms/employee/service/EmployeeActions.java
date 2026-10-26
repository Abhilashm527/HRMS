package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The sixteen things Roles → Employee permissions can grant, as one question: may the caller do {@code action} to this
 * person? Until per-role scope grants exist they map onto the employee module permission (View / Edit / Approve / Admin)
 * plus the relationship rules (own record, manager) held in OpenFGA.
 */
@Component("employeeActions")
public class EmployeeActions {

	private static final Map<String, String> LEVEL = Map.ofEntries(Map.entry("create_employee", "EDIT"), Map.entry("update_employee", "EDIT"),
			Map.entry("change_job", "EDIT"), Map.entry("change_manager", "EDIT"), Map.entry("change_status", "EDIT"), Map.entry("upload_docs", "EDIT"),
			Map.entry("view_history", "EDIT"), Map.entry("initiate_exit", "APPROVE"), Map.entry("approve_changes", "APPROVE"),
			Map.entry("verify_docs", "APPROVE"), Map.entry("delete_employee", "ADMIN"), Map.entry("export_employees", "ADMIN"),
			Map.entry("import_employees", "ADMIN"));

	private final ModuleAccess access;
	private final AuthzService authz;
	private final CurrentUser currentUser;

	EmployeeActions(ModuleAccess access, AuthzService authz, CurrentUser currentUser) {
		this.access = access;
		this.authz = authz;
		this.currentUser = currentUser;
	}

	public boolean can(String action, UUID target) {
		UUID me = currentUser.employeeId();
		if (me == null) {
			return false;
		}
		boolean self = target != null && me.equals(target);
		return switch (action) {
			case "view_directory" -> authz.isMember() || access.has("employee", "VIEW");
			case "view_profile" -> self || (target != null && authz.can("can_view_basic", "employee:" + target));
			case "print_idcard" -> self || access.has("employee", "EDIT");
			default -> {
				String level = LEVEL.get(action);
				yield level != null && access.has("employee", level);
			}
		};
	}

	public void require(String action, UUID target) {
		if (!can(action, target)) {
			throw new ForbiddenException("You do not have permission for that (" + action + ")");
		}
	}

	/** Every action as the profile header needs it. */
	public Map<String, Boolean> forProfile(UUID target) {
		Map<String, Boolean> out = new LinkedHashMap<>();
		out.put("canEdit", can("update_employee", target));
		out.put("canChangeJob", can("change_job", target));
		out.put("canChangeManager", can("change_manager", target));
		out.put("canChangeStatus", can("change_status", target));
		out.put("canExit", can("initiate_exit", target));
		out.put("canArchive", can("delete_employee", target));
		out.put("canUploadDocs", can("upload_docs", target) || can("view_profile", target) && currentUser.employeeId() != null && currentUser.employeeId().equals(target));
		out.put("canVerifyDocs", can("verify_docs", target));
		out.put("canViewHistory", can("view_history", target));
		out.put("canPrintIdCard", can("print_idcard", target));
		out.put("canChangePhoto", can("update_employee", target) || (currentUser.employeeId() != null && currentUser.employeeId().equals(target)));
		return out;
	}
}
