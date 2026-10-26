package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.SalaryVisibilityChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.domain.ConfigFieldAccessId;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.repo.ConfigFieldAccessRepository;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * "Salary visibility" in the organization settings presets the compensation fields of the employee profile: what
 * department heads, managers, auditors and employees see on other people's records. It is a one-time write, not a
 * lock: admins can still change single cells in Field security afterwards. Super admin, HR admin and finance, and
 * everyone's own side, are not touched. Roles that do not exist yet are skipped.
 */
@Component
class SalaryVisibilityPreset {

	private static final String SECTION = "compensation";

	/** Role key to level, per preset (ORGANIZATION_BACKEND_GUIDE.md §6). */
	private static final Map<String, Map<String, FieldAccess>> PRESETS = Map.of(
			"ADMINS_ONLY", levels(FieldAccess.HIDDEN, FieldAccess.HIDDEN, FieldAccess.MASKED, FieldAccess.HIDDEN),
			"HR_FINANCE", levels(FieldAccess.MASKED, FieldAccess.HIDDEN, FieldAccess.MASKED, FieldAccess.HIDDEN),
			"MANAGERS_HR", levels(FieldAccess.VIEW, FieldAccess.MASKED, FieldAccess.MASKED, FieldAccess.HIDDEN),
			"EVERYONE", levels(FieldAccess.VIEW, FieldAccess.VIEW, FieldAccess.VIEW, FieldAccess.VIEW));

	private final AreaCatalog catalog;
	private final ConfigFieldAccessRepository accessRepo;
	private final IamDirectory iam;
	private final CurrentUser currentUser;
	private final Audit audit;

	SalaryVisibilityPreset(AreaCatalog catalog, ConfigFieldAccessRepository accessRepo, IamDirectory iam,
			CurrentUser currentUser, Audit audit) {
		this.catalog = catalog;
		this.accessRepo = accessRepo;
		this.iam = iam;
		this.currentUser = currentUser;
		this.audit = audit;
	}

	@EventListener
	void on(SalaryVisibilityChangedEvent event) {
		Map<String, FieldAccess> preset = PRESETS.get(event.value());
		if (preset == null) {
			return;
		}
		Set<UUID> sections = catalog.sections(AreaCatalog.PROFILE).stream().filter(s -> SECTION.equals(s.getSectionKey()))
				.map(ConfigSection::getId).collect(Collectors.toSet());
		List<ConfigField> fields = catalog.fields(AreaCatalog.PROFILE).stream()
				.filter(f -> sections.contains(f.getSectionId())).toList();
		Map<String, Object> written = new LinkedHashMap<>();
		for (Map.Entry<String, FieldAccess> e : preset.entrySet()) {
			RoleRef role = iam.roleByKey(e.getKey()).orElse(null);
			if (role == null) {
				continue;
			}
			for (ConfigField f : fields) {
				ConfigFieldAccess rule = accessRepo.findById(new ConfigFieldAccessId(f.getId(), role.id()))
						.orElseGet(() -> new ConfigFieldAccess(f.getId(), role.id(), null, null, null));
				rule.setOthersAccess(e.getValue());
				accessRepo.save(rule);
				written.put(f.getFieldKey() + "/" + role.key(), e.getValue());
			}
		}
		accessRepo.flush();
		audit.publish("config", "ConfigFieldAccess", SECTION, "PRESET", null, Map.of("preset", event.value(), "cells", written));
	}

	private static Map<String, FieldAccess> levels(FieldAccess deptHead, FieldAccess manager, FieldAccess auditor,
			FieldAccess employee) {
		Map<String, FieldAccess> m = new LinkedHashMap<>();
		m.put("dept_head", deptHead);
		m.put("manager", manager);
		m.put("auditor", auditor);
		m.put("employee", employee);
		return m;
	}
}
