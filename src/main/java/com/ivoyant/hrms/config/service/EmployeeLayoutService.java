package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeeLayoutChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.dto.CreateFieldRequest;
import com.ivoyant.hrms.config.repo.ConfigFieldAccessRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldRepository;
import com.ivoyant.hrms.config.repo.ConfigSectionRepository;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Configuration → Employee fields: the employee profile's sections and fields as one document the screen edits and saves
 * together (Unsaved changes → Save / Discard). Saving compares the document with what is stored and creates, updates, reorders or hides
 * what changed. Hiding is a soft delete: what people entered stays, and a built-in section can be restored.
 */
@Service
public class EmployeeLayoutService {

	private static final String AREA = AreaCatalog.PROFILE;
	/** The Job tab's own sections: configurable elsewhere, not on this screen. */
	private static final Set<String> HIDDEN_SECTIONS = Set.of("basic", "work");

	private final AreaCatalog catalog;
	private final ConfigService config;
	private final ConfigSectionRepository sectionRepo;
	private final ConfigFieldRepository fieldRepo;
	private final ConfigFieldAccessRepository accessRepo;
	private final IamDirectory iam;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public EmployeeLayoutService(AreaCatalog catalog, ConfigService config, ConfigSectionRepository sectionRepo, ConfigFieldRepository fieldRepo,
			ConfigFieldAccessRepository accessRepo, IamDirectory iam, JdbcClient jdbc, CurrentUser currentUser, Audit audit,
			ApplicationEventPublisher events) {
		this.catalog = catalog;
		this.config = config;
		this.sectionRepo = sectionRepo;
		this.fieldRepo = fieldRepo;
		this.accessRepo = accessRepo;
		this.iam = iam;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reading

	@Transactional(readOnly = true)
	public Map<String, Object> get() {
		requireOwner();
		UUID org = currentUser.orgId();
		List<ConfigSection> all = sectionRepo.findByOrgIdAndAreaKeyOrderBySortAscNameAsc(org, AREA);
		List<ConfigField> fields = fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(org, AREA);
		UUID employeeRole = employeeRole();
		Map<UUID, FieldAccess> own = new HashMap<>();
		accessRepo.findByFieldIdIn(fields.stream().map(ConfigField::getId).toList()).stream().filter(a -> a.getRoleId().equals(employeeRole))
				.forEach(a -> own.put(a.getFieldId(), a.getOwnAccess()));
		Map<String, Long> withValues = valueCounts();
		List<Map<String, Object>> sections = new ArrayList<>();
		List<Map<String, Object>> restorable = new ArrayList<>();
		for (ConfigSection s : all) {
			if (HIDDEN_SECTIONS.contains(s.getSectionKey())) {
				continue;
			}
			if (s.getDeletedAt() != null) {
				if (s.isBuiltin()) {
					restorable.add(Map.of("key", s.getSectionKey(), "name", s.getName()));
				}
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", s.getId().toString());
			m.put("key", s.getSectionKey());
			m.put("name", s.getName());
			m.put("on", s.isActive());
			m.put("onb", s.isShowInPortal());
			m.put("builtin", s.isBuiltin());
			m.put("fromCustomFields", "custom".equals(s.getSectionKey()));
			m.put("sort", s.getSort());
			m.put("fields", fields.stream().filter(f -> f.getSectionId().equals(s.getId()) && f.getDeletedAt() == null).map(f -> {
				Map<String, Object> fm = new LinkedHashMap<>();
				fm.put("id", f.getId().toString());
				fm.put("key", f.getFieldKey());
				fm.put("label", f.getLabel());
				fm.put("type", label(f.getFieldType()));
				fm.put("options", FieldValues.parseOptions(f.getOptions()));
				fm.put("required", f.isRequired());
				fm.put("access", preset(f, own.get(f.getId())));
				fm.put("source", f.getSource());
				fm.put("sort", f.getSort());
				fm.put("hasData", f.isSystem() || withValues.getOrDefault(f.getFieldKey(), 0L) > 0);
				return fm;
			}).toList());
			sections.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("version", version());
		out.put("sections", sections);
		out.put("restorable", restorable);
		return out;
	}

	private Map<String, Long> valueCounts() {
		Map<String, Long> out = new HashMap<>();
		jdbc.sql("select k, count(*) from employee e, jsonb_object_keys(e.custom) k where e.org_id = :org group by k").param("org", currentUser.orgId())
				.query((rs, i) -> {
					out.put(rs.getString(1), rs.getLong(2));
					return 0;
				}).list();
		return out;
	}

	private long version() {
		return jdbc.sql("select version from config_layout_version where org_id = :org and area_key = :area").param("org", currentUser.orgId())
				.param("area", AREA).query(Long.class).optional().orElse(0L);
	}

	// ================================================================== saving

	/**
	 * The whole layout: {@code sections: [{id, name, on, onb, fields: [{id, label, type, options, required, access}]}]}. Order is the
	 * order of the arrays. Sections and fields that are missing are hidden (soft delete).
	 */
	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> save(Map<String, Object> body, String ifMatch, boolean force) {
		requireOwner();
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(version()))) {
			throw new StaleException("The employee fields were changed by someone else. Reload and try again.");
		}
		if (!(body.get("sections") instanceof List<?> incoming)) {
			throw invalid("sections", "REQUIRED", "sections is required");
		}
		UUID org = currentUser.orgId();
		List<ConfigSection> storedSections = sectionRepo.findByOrgIdAndAreaKeyOrderBySortAscNameAsc(org, AREA);
		Map<UUID, ConfigSection> sectionById = storedSections.stream().collect(Collectors.toMap(ConfigSection::getId, s -> s));
		Map<UUID, ConfigField> fieldById = fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(org, AREA).stream().collect(Collectors.toMap(ConfigField::getId, f -> f));
		List<ErrorItem> errors = new ArrayList<>();
		// names are unique ignoring case among the sections that stay
		Set<String> names = new HashSet<>();
		storedSections.stream().filter(s -> HIDDEN_SECTIONS.contains(s.getSectionKey()) && s.getDeletedAt() == null).forEach(s -> names.add(s.getName().toLowerCase(Locale.ROOT)));
		int added = 0;
		int removed = 0;
		int changed = 0;
		Set<UUID> keptSections = new HashSet<>();
		Set<UUID> keptFields = new HashSet<>();
		int sort = 30;
		for (int i = 0; i < incoming.size(); i++) {
			Map<String, Object> sm = (Map<String, Object>) incoming.get(i);
			String name = text(sm.get("name"));
			String path = "sections[" + i + "]";
			if (name == null) {
				errors.add(ErrorItem.field(path + ".name", "REQUIRED", "Every section needs a name"));
				continue;
			}
			if (!names.add(name.toLowerCase(Locale.ROOT))) {
				errors.add(ErrorItem.field(path + ".name", "SECTION_NAME_TAKEN", "There is already a section called " + name));
				continue;
			}
			UUID sid = uuid(sm.get("id"));
			ConfigSection s = sid == null ? null : sectionById.get(sid);
			boolean on = !Boolean.FALSE.equals(sm.get("on"));
			boolean onb = Boolean.TRUE.equals(sm.get("onb"));
			if (s != null && s.isBuiltin() && onb) {
				errors.add(ErrorItem.field(path + ".onb", "INVALID", s.getName() + " is a built-in section and can't be an onboarding step"));
				onb = false;
			}
			if (s == null) {
				s = new ConfigSection();
				s.setOrgId(org);
				s.setAreaKey(AREA);
				s.setSectionKey(uniqueKey(slug(name)));
				added++;
			}
			else {
				keptSections.add(s.getId());
				if (!name.equals(s.getName()) || on != s.isActive() || onb != s.isShowInPortal() || s.getSort() != sort || s.getDeletedAt() != null) {
					changed++;
				}
			}
			s.setName(name);
			s.setActive(on);
			s.setShowInPortal(onb);
			s.setSort(sort);
			s.setDeletedAt(null);
			sectionRepo.saveAndFlush(s);
			keptSections.add(s.getId());
			sort += 10;
			if (s.isLocked() || s.isBuiltin() || "custom".equals(s.getSectionKey())) {
				// built-in sections are drawn by code and the custom section comes from Settings → Custom fields: only the name and on/off change here
				final UUID keepId = s.getId();
				fieldById.values().stream().filter(f -> f.getSectionId().equals(keepId)).forEach(f -> keptFields.add(f.getId()));
				continue;
			}
			List<Map<String, Object>> fields = sm.get("fields") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
			int fsort = 10;
			Set<String> labels = new HashSet<>();
			for (int j = 0; j < fields.size(); j++) {
				Map<String, Object> fm = fields.get(j);
				String fpath = path + ".fields[" + j + "]";
				String label = text(fm.get("label"));
				if (label == null) {
					errors.add(ErrorItem.field(fpath + ".label", "REQUIRED", "Every field needs a label"));
					continue;
				}
				if (!labels.add(label.toLowerCase(Locale.ROOT))) {
					errors.add(ErrorItem.field(fpath + ".label", "INVALID", "There is already a field called " + label + " in " + name));
				}
				FieldType type = type(fm.get("type"));
				if (type == null) {
					errors.add(ErrorItem.field(fpath + ".type", "INVALID", "Unknown field type '" + fm.get("type") + "'"));
					continue;
				}
				List<String> options = FieldValues.parseOptions(fm.get("options"));
				if (type == FieldType.SELECT && options.isEmpty()) {
					errors.add(ErrorItem.field(fpath + ".options", "REQUIRED", label + " is a dropdown and needs options"));
				}
				boolean required = Boolean.TRUE.equals(fm.get("required")) && type.hasValue();
				String access = fm.get("access") == null ? "EDIT" : fm.get("access").toString().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
				if (!Set.of("EDIT", "HR_APPROVES", "VIEW", "HIDDEN").contains(access)) {
					errors.add(ErrorItem.field(fpath + ".access", "INVALID", "Access must be EDIT, HR_APPROVES, VIEW or HIDDEN"));
					continue;
				}
				UUID fid = uuid(fm.get("id"));
				ConfigField f = fid == null ? null : fieldById.get(fid);
				if (f != null && f.getSource() != null) {
					boolean touched = !label.equals(f.getLabel()) || type != f.getFieldType();
					if (touched) {
						errors.add(ErrorItem.field(fpath, "SOURCE_LOCKED", f.getLabel() + " comes from " + f.getSource() + " and can't be changed here"));
					}
				}
				if (!errors.isEmpty()) {
					continue;
				}
				UUID target;
				if (f == null) {
					var created = config.createField(AREA, new CreateFieldRequest(s.getId(), label, type, null, required, null, null, options, null, false));
					target = created.id();
					added++;
					f = fieldRepo.findById(target).orElseThrow();
				}
				else {
					target = f.getId();
					Map<String, Object> patch = new HashMap<>();
					if (f.getSource() == null) {
						patch.put("label", label);
						patch.put("required", required);
						patch.put("options", options);
						if (type != f.getFieldType()) {
							patch.put("type", type.name());
						}
					}
					if (!f.isSystem() || f.getSource() == null) {
						config.updateField(target, patch, force, null);
					}
					if (!f.getSectionId().equals(s.getId())) {
						f.setSectionId(s.getId());
					}
					changed++;
				}
				f = fieldRepo.findById(target).orElseThrow();
				f.setSectionId(s.getId());
				f.setSort(fsort);
				f.setDeletedAt(null);
				f.setActive(true);
				if (f.getSource() == null) {
					f.setAccessPreset(access);
				}
				fieldRepo.saveAndFlush(f);
				if (f.getSource() == null) {
					writeOwnAccess(f.getId(), access);
				}
				keptFields.add(target);
				fsort += 10;
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		// what is no longer listed is hidden, not deleted
		for (ConfigSection s : storedSections) {
			if (HIDDEN_SECTIONS.contains(s.getSectionKey()) || keptSections.contains(s.getId()) || s.getDeletedAt() != null) {
				continue;
			}
			s.setDeletedAt(Instant.now());
			s.setActive(false);
			sectionRepo.saveAndFlush(s);
			removed++;
		}
		for (ConfigField f : fieldById.values()) {
			ConfigSection s = sectionById.get(f.getSectionId());
			if (s == null || HIDDEN_SECTIONS.contains(s.getSectionKey()) || keptFields.contains(f.getId()) || f.getDeletedAt() != null || f.isSystem()) {
				continue;
			}
			if (keptSections.contains(s.getId())) {
				f.setDeletedAt(Instant.now());
				f.setActive(false);
				fieldRepo.saveAndFlush(f);
				removed++;
			}
		}
		long v = bump();
		audit.publish("config", "EmployeeLayout", AREA, "SAVE", null,
				Map.of("summary", "Employee fields saved: added " + added + ", changed " + changed + ", removed " + removed));
		events.publishEvent(new EmployeeLayoutChangedEvent(v));
		return get();
	}

	/** Brings a built-in section back (it comes back switched on, with its fields). */
	@Transactional
	public Map<String, Object> restore(String sectionKey) {
		requireOwner();
		ConfigSection s = sectionRepo.findByOrgIdAndAreaKeyOrderBySortAscNameAsc(currentUser.orgId(), AREA).stream()
				.filter(x -> x.getSectionKey().equals(sectionKey)).findFirst().orElseThrow(() -> new NotFoundException("There is no section '" + sectionKey + "'"));
		if (!s.isBuiltin() || s.getDeletedAt() == null) {
			throw invalid("sectionKey", "INVALID", s.getName() + " isn't a removed built-in section");
		}
		s.setDeletedAt(null);
		s.setActive(true);
		sectionRepo.saveAndFlush(s);
		long v = bump();
		audit.publish("config", "EmployeeLayout", AREA, "RESTORE", null, Map.of("summary", s.getName() + " restored"));
		events.publishEvent(new EmployeeLayoutChangedEvent(v));
		return get();
	}

	// ================================================================== "New fields" badge in Module permissions

	/** Fields created since the role last confirmed it had looked at them. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> newFields(UUID roleId) {
		requireOwner();
		Instant since = jdbc.sql("select coalesce((select acked_at from config_field_ack where org_id = :org and role_id = :r), (select r.created_at from role r where r.id = :r))")
				.param("org", currentUser.orgId()).param("r", roleId).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant)
				.orElseThrow(() -> new NotFoundException("Role " + roleId + " not found"));
		return fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(currentUser.orgId(), AREA).stream()
				.filter(f -> f.getDeletedAt() == null && f.getCreatedAt() != null && f.getCreatedAt().isAfter(since)).map(f -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", f.getId().toString());
					m.put("key", f.getFieldKey());
					m.put("label", f.getLabel());
					return m;
				}).toList();
	}

	@Transactional
	public void ackNewFields(UUID roleId) {
		requireOwner();
		jdbc.sql("""
				insert into config_field_ack (org_id, role_id, acked_at) values (:org, :r, now())
				on conflict (org_id, role_id) do update set acked_at = now()""").param("org", currentUser.orgId()).param("r", roleId).update();
	}

	// ================================================================== helpers

	private void requireOwner() {
		if (!config.canConfigure(AREA)) {
			throw new ForbiddenException("You cannot change the employee fields");
		}
	}

	private long bump() {
		return jdbc.sql("""
				insert into config_layout_version (org_id, area_key, version, updated_by) values (:org, :area, 1, :by)
				on conflict (org_id, area_key) do update set version = config_layout_version.version + 1, updated_at = now(), updated_by = :by
				returning version""").param("org", currentUser.orgId()).param("area", AREA).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
				.query(Long.class).single();
	}

	private UUID employeeRole() {
		return iam.roles().stream().filter(r -> "employee".equals(r.key())).map(RoleRef::id).findFirst().orElse(null);
	}

	/** The employee role's own-side access for a field becomes the preset (an explicit rule: it replaces a generated one). */
	private void writeOwnAccess(UUID fieldId, String preset) {
		UUID role = employeeRole();
		if (role == null) {
			return;
		}
		FieldAccess own = FieldAccess.valueOf(preset);
		ConfigFieldAccess row = accessRepo.findByFieldId(fieldId).stream().filter(a -> a.getRoleId().equals(role)).findFirst()
				.orElseGet(() -> new ConfigFieldAccess(fieldId, role, null, null, null));
		row.setOwnAccess(own);
		row.setGeneratedRule(false);
		accessRepo.saveAndFlush(row);
	}

	private String preset(ConfigField f, FieldAccess own) {
		if (f.getAccessPreset() != null) {
			return f.getAccessPreset();
		}
		return own == null ? "EDIT" : own.name();
	}

	private String uniqueKey(String base) {
		String key = base;
		int n = 2;
		while (sectionRepo.existsByOrgIdAndAreaKeyAndSectionKey(currentUser.orgId(), AREA, key)) {
			key = base + "_" + n++;
		}
		return key;
	}

	private static String slug(String name) {
		String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return s.isEmpty() ? "section" : s;
	}

	private static String text(Object o) {
		return o == null || o.toString().isBlank() ? null : o.toString().trim();
	}

	private static UUID uuid(Object o) {
		try {
			return o == null ? null : UUID.fromString(o.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String label(FieldType t) {
		return switch (t) {
			case TEXT -> "Text";
			case TEXTAREA -> "Long text";
			case NUMBER, CURRENCY -> "Number";
			case DATE -> "Date";
			case EMAIL -> "Email";
			case PHONE -> "Phone";
			case SELECT -> "Dropdown";
			case FILE -> "File";
			case HEADING -> "Heading";
			default -> FieldBackedLists.uiType(t);
		};
	}

	private static FieldType type(Object ui) {
		if (ui == null) {
			return null;
		}
		return switch (ui.toString().trim().toLowerCase(Locale.ROOT)) {
			case "text" -> FieldType.TEXT;
			case "long text", "textarea" -> FieldType.TEXTAREA;
			case "number" -> FieldType.NUMBER;
			case "date" -> FieldType.DATE;
			case "email" -> FieldType.EMAIL;
			case "phone" -> FieldType.PHONE;
			case "dropdown", "select" -> FieldType.SELECT;
			case "file" -> FieldType.FILE;
			case "heading" -> FieldType.HEADING;
			default -> null;
		};
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
