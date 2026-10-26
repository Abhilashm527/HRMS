package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.repo.ConfigFieldAccessRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldRepository;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A custom field's "Visible to" and "Employee sees" become access rules. Who may see other people's values: Everyone, Managers &amp; HR,
 * or Admins only; HR admins and super admins keep their normal level. The employee role's own side is View or Hidden. Rules are marked as
 * generated, so an explicit choice made in Field security is never overwritten and wins over these defaults.
 */
@Component
class CustomFieldAccess {

	private static final Set<String> ADMINS = Set.of("super_admin", "hr_admin");
	private static final Set<String> MANAGERS_HR = Set.of("super_admin", "hr_admin", "manager");

	private final ConfigFieldRepository fields;
	private final ConfigFieldAccessRepository access;
	private final IamDirectory iam;

	CustomFieldAccess(ConfigFieldRepository fields, ConfigFieldAccessRepository access, IamDirectory iam) {
		this.fields = fields;
		this.access = access;
		this.iam = iam;
	}

	static String scopeOf(Object label) {
		String t = label == null ? "" : label.toString().trim().toLowerCase();
		return switch (t) {
			case "managers & hr", "managers_hr", "managers and hr" -> "MANAGERS_HR";
			case "admins only", "admins", "admin" -> "ADMINS";
			default -> "EVERYONE";
		};
	}

	static String labelOf(String scope) {
		return switch (scope == null ? "EVERYONE" : scope) {
			case "MANAGERS_HR" -> "Managers & HR";
			case "ADMINS" -> "Admins only";
			default -> "Everyone";
		};
	}

	/** Stores the two choices on the field and rewrites its generated access rules. {@code employeeField} is true for the Employee entity. */
	void apply(UUID fieldId, String accLabel, boolean employeeSees, boolean employeeField) {
		ConfigField f = fields.findById(fieldId).orElseThrow();
		String scope = scopeOf(accLabel);
		f.setAccessScope(scope);
		f.setEmployeeSees(employeeSees);
		fields.saveAndFlush(f);
		access.deleteByFieldIdAndGeneratedRuleTrue(fieldId);
		access.flush();
		Set<UUID> explicit = access.findByFieldId(fieldId).stream().map(ConfigFieldAccess::getRoleId).collect(Collectors.toSet());
		for (RoleRef role : iam.roles()) {
			if (explicit.contains(role.id()) || ADMINS.contains(role.key())) {
				continue;
			}
			boolean sees = switch (scope) {
				case "ADMINS" -> false;
				case "MANAGERS_HR" -> MANAGERS_HR.contains(role.key());
				default -> true;
			};
			FieldAccess others = sees ? FieldAccess.VIEW : FieldAccess.HIDDEN;
			FieldAccess own = employeeField && "employee".equals(role.key()) ? employeeSees ? FieldAccess.VIEW : FieldAccess.HIDDEN : null;
			ConfigFieldAccess row = new ConfigFieldAccess(fieldId, role.id(), null, own, others);
			row.setGeneratedRule(true);
			access.save(row);
		}
		access.flush();
	}
}
