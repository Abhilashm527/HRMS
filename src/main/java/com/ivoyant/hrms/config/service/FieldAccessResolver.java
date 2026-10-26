package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.repo.ConfigFieldAccessRepository;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one place that decides what a caller may do with a field.
 * <p>
 * Per role: an explicit rule in {@code config_field_access} wins (its own/others side, falling back to
 * {@code access}); with no rule the role's permission level in the area's module decides. The caller's level is the
 * strongest over all their roles (own roles plus roles received through an active delegation); super admins always
 * edit. A field fed by another module is capped at VIEW.
 */
@Component
public class FieldAccessResolver {

	public static final String SUPER_ADMIN = "super_admin";
	public static final String EMPLOYEE = "employee";

	public enum Side { OWN, OTHERS }

	/** A role's level on a field and whether an explicit rule (rather than the default) produced it. */
	public record RoleLevel(FieldAccess level, boolean explicit) {
	}

	private final IamDirectory iam;
	private final ConfigFieldAccessRepository accessRepo;

	public FieldAccessResolver(IamDirectory iam, ConfigFieldAccessRepository accessRepo) {
		this.iam = iam;
		this.accessRepo = accessRepo;
	}

	public static Side side(UUID ownerEmployeeId, UUID callerId) {
		return ownerEmployeeId != null && ownerEmployeeId.equals(callerId) ? Side.OWN : Side.OTHERS;
	}

	/** Level of every field for the caller, by field key. */
	public Map<String, FieldAccess> levels(ConfigArea area, List<ConfigField> fields, UUID callerId,
			UUID ownerEmployeeId) {
		Side side = side(ownerEmployeeId, callerId);
		Set<RoleRef> roles = iam.effectiveRoles(callerId, area.getPermModule());
		Map<UUID, PermLevel> perms = iam.permissionLevels(area.getPermModule());
		Map<UUID, Map<UUID, ConfigFieldAccess>> rules = rulesByField(fields);
		boolean superAdmin = roles.stream().anyMatch(r -> SUPER_ADMIN.equals(r.key()));

		Map<String, FieldAccess> out = new HashMap<>();
		for (ConfigField f : fields) {
			FieldAccess best = FieldAccess.HIDDEN;
			boolean explicitView = false;
			for (RoleRef role : roles) {
				RoleLevel rl = roleLevel(role, f, side, rules.getOrDefault(f.getId(), Map.of()).get(role.id()),
						perms.getOrDefault(role.id(), PermLevel.NONE));
				best = FieldAccess.max(best, rl.level());
				explicitView |= rl.explicit() && rl.level().atLeast(FieldAccess.VIEW);
			}
			if (superAdmin) {
				best = FieldAccess.EDIT;
			}
			if (f.getSource() != null && best.atLeast(FieldAccess.VIEW)) {
				best = FieldAccess.VIEW;
			}
			if (f.isMaskedDefault() && best == FieldAccess.VIEW && !explicitView) {
				best = FieldAccess.MASKED;
			}
			out.put(f.getFieldKey(), best);
		}
		return out;
	}

	/** One role's level on a field; also used to draw the access matrix. */
	public RoleLevel roleLevel(RoleRef role, ConfigField field, Side side, ConfigFieldAccess rule, PermLevel perm) {
		FieldAccess level;
		boolean explicit = false;
		if (SUPER_ADMIN.equals(role.key())) {
			level = FieldAccess.EDIT;
		}
		else {
			FieldAccess ruled = null;
			if (rule != null) {
				FieldAccess own = rule.getOwnAccess() != null ? rule.getOwnAccess() : rule.getAccess();
				FieldAccess others = rule.getOthersAccess() != null ? rule.getOthersAccess() : rule.getAccess();
				ruled = side == Side.OWN ? own : others;
			}
			explicit = ruled != null;
			level = explicit ? ruled : defaultLevel(role, perm, side);
		}
		if (field.getSource() != null && level.atLeast(FieldAccess.VIEW)) {
			level = FieldAccess.VIEW;
		}
		return new RoleLevel(level, explicit);
	}

	/** What a role gets on a field it has no rule for, from its permission level in the area's module. */
	public static FieldAccess defaultLevel(RoleRef role, PermLevel perm, Side side) {
		boolean employee = EMPLOYEE.equals(role.key());
		if (side == Side.OWN) {
			if (employee || perm.atLeast(PermLevel.EDIT)) {
				return FieldAccess.EDIT;
			}
			return perm.atLeast(PermLevel.VIEW) ? FieldAccess.VIEW : FieldAccess.HIDDEN;
		}
		if (employee) {
			return FieldAccess.HIDDEN;
		}
		if (perm.atLeast(PermLevel.EDIT)) {
			return FieldAccess.EDIT;
		}
		return perm.atLeast(PermLevel.VIEW) ? FieldAccess.VIEW : FieldAccess.HIDDEN;
	}

	public Map<UUID, Map<UUID, ConfigFieldAccess>> rulesByField(List<ConfigField> fields) {
		if (fields.isEmpty()) {
			return Map.of();
		}
		return accessRepo.findByFieldIdIn(fields.stream().map(ConfigField::getId).toList()).stream()
				.collect(Collectors.groupingBy(ConfigFieldAccess::getFieldId,
						Collectors.toMap(ConfigFieldAccess::getRoleId, a -> a)));
	}
}
