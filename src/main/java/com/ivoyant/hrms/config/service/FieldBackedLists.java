package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.dto.CreateFieldRequest;
import com.ivoyant.hrms.config.dto.CreateSectionRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Settings lists whose rows are really form fields: the hire form's fields ({@code onboarding/fields}) and the
 * employee custom fields ({@code onboarding/cfields}, entity Employee). They are read from and written to
 * {@code config_field} through {@link ConfigService}, so the same rules (guards, audit, access) apply.
 */
@Component
class FieldBackedLists {

	private static final String ONBOARDING = AreaCatalog.ONBOARDING;
	private static final String PROFILE = AreaCatalog.PROFILE;
	private static final String CUSTOM_SECTION = "custom";

	/** The settings UI's type labels (both spellings of long text are accepted when reading). */
	private static final Map<String, FieldType> TYPES = Map.ofEntries(Map.entry("Text", FieldType.TEXT),
			Map.entry("Number", FieldType.NUMBER), Map.entry("Date", FieldType.DATE),
			Map.entry("Dropdown", FieldType.SELECT), Map.entry("Multi-select", FieldType.MULTISELECT),
			Map.entry("Yes / No", FieldType.BOOLEAN), Map.entry("Email", FieldType.EMAIL),
			Map.entry("Phone", FieldType.PHONE), Map.entry("Textarea", FieldType.TEXTAREA),
			Map.entry("Long text", FieldType.TEXTAREA), Map.entry("Department", FieldType.DEPARTMENT),
			Map.entry("Employee", FieldType.EMPLOYEE), Map.entry("Location", FieldType.LOCATION),
			Map.entry("Employment type", FieldType.EMPLOYMENT_TYPE));

	private final AreaCatalog catalog;
	private final ConfigService config;
	private final CustomFieldAccess customAccess;
	private final com.ivoyant.hrms.config.repo.ConfigFieldRepository fieldRepo;

	FieldBackedLists(AreaCatalog catalog, ConfigService config, CustomFieldAccess customAccess,
			com.ivoyant.hrms.config.repo.ConfigFieldRepository fieldRepo) {
		this.catalog = catalog;
		this.config = config;
		this.customAccess = customAccess;
		this.fieldRepo = fieldRepo;
	}

	// ---- onboarding / fields ----

	List<Map<String, Object>> loadHireFields() {
		Map<UUID, ConfigSection> sections = AreaCatalog.byId(catalog.sections(ONBOARDING), ConfigSection::getId);
		return catalog.fields(ONBOARDING).stream().filter(f -> f.getFieldType().hasValue()).map(f -> {
			Map<String, Object> row = new LinkedHashMap<>();
			ConfigSection s = sections.get(f.getSectionId());
			row.put("id", f.getId().toString());
			row.put("scope", s == null ? "" : s.getName());
			row.put("name", f.getLabel());
			row.put("ftype", uiType(f.getFieldType()));
			row.put("opts", String.join(", ", FieldValues.parseOptions(f.getOptions())));
			row.put("req", f.isRequired());
			row.put("active", f.isActive());
			row.put("key", f.getFieldKey());
			row.put("lock", f.isSystem());
			return row;
		}).toList();
	}

	void saveHireFields(List<Map<String, Object>> rows) {
		Map<UUID, ConfigField> existing = AreaCatalog.byId(catalog.fields(ONBOARDING), ConfigField::getId);
		List<ConfigSection> sections = catalog.sections(ONBOARDING).stream().filter(s -> !s.isLocked()).toList();
		Set<UUID> kept = new java.util.HashSet<>();
		for (Map<String, Object> row : rows) {
			UUID id = UUID.fromString(row.get("id").toString());
			kept.add(id);
			ConfigField current = existing.get(id);
			if (current == null) {
				UUID sectionId = sectionByName(sections, str(row.get("scope"))).getId();
				var created = config.createField(ONBOARDING, new CreateFieldRequest(sectionId, str(row.get("name")),
						type(row.get("ftype")), blank(row.get("key")) ? null : str(row.get("key")),
						bool(row.get("req")), null, null, row.get("opts"), null, false));
				if (!bool(row.getOrDefault("active", true))) {
					config.updateField(created.id(), Map.of("active", false), true, null);
				}
			}
			else {
				config.updateField(id, patchOf(current, row), true, null);
			}
		}
		for (ConfigField f : existing.values()) {
			if (!kept.contains(f.getId()) && !f.isSystem() && f.getFieldType().hasValue()) {
				config.deleteField(f.getId(), true);
			}
		}
	}

	// ---- onboarding / cfields: entities whose custom fields are form fields ----

	/** The entity a custom-field row applies to, and the config area that holds its fields. */
	static final Map<String, String> ENTITY_AREAS = Map.of("Employee", PROFILE, "Department", "department",
			"Position", "position", "Title", "title");

	List<Map<String, Object>> loadCustomFields(Map<String, Map<String, Object>> meta) {
		List<Map<String, Object>> out = new ArrayList<>();
		ENTITY_AREAS.forEach((entity, area) -> customFields(area).forEach(f -> {
			Map<String, Object> row = new LinkedHashMap<>();
			Map<String, Object> m = meta.getOrDefault(f.getId().toString(), Map.of());
			row.put("id", f.getId().toString());
			row.put("ent", entity);
			row.put("name", f.getLabel());
			row.put("key", f.getFieldKey());
			row.put("ftype", uiType(f.getFieldType()));
			row.put("opts", String.join(", ", FieldValues.parseOptions(f.getOptions())));
			row.put("req", f.isRequired());
			row.put("emp", f.isEmployeeSees());
			row.put("acc", CustomFieldAccess.labelOf(f.getAccessScope()));
			row.put("mask", f.isMaskedDefault());
			row.put("active", f.isActive());
			out.add(row);
		}));
		return out;
	}

	/** Saves the rows of every entity that has form fields; returns the id→{emp, acc} settings to keep alongside. */
	Map<String, Map<String, Object>> saveCustomFields(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> meta = new HashMap<>();
		ENTITY_AREAS.forEach((entity, area) -> meta.putAll(saveEntityFields(area,
				rows.stream().filter(r -> entity.equals(String.valueOf(r.get("ent")))).toList())));
		return meta;
	}

	private Map<String, Map<String, Object>> saveEntityFields(String area, List<Map<String, Object>> entityRows) {
		ConfigSection custom = catalog.sections(area).stream().filter(s -> CUSTOM_SECTION.equals(s.getSectionKey()))
				.findFirst().orElseGet(() -> {
					var created = config.createSection(area, new CreateSectionRequest("Additional details", CUSTOM_SECTION, null));
					return catalog.sections(area).stream().filter(s -> s.getId().equals(created.id())).findFirst().orElseThrow();
				});
		Map<UUID, ConfigField> existing = AreaCatalog.byId(customFields(area), ConfigField::getId);
		Set<UUID> kept = new java.util.HashSet<>();
		Map<String, Map<String, Object>> meta = new HashMap<>();
		for (Map<String, Object> row : entityRows) {
			UUID id = UUID.fromString(row.get("id").toString());
			kept.add(id);
			UUID fieldId = id;
			ConfigField current = existing.get(id);
			if (current == null) {
				var created = config.createField(area, new CreateFieldRequest(custom.getId(), str(row.get("name")),
						type(row.get("ftype")), blank(row.get("key")) ? null : str(row.get("key")),
						bool(row.get("req")), null, null, row.get("opts"), null, bool(row.get("mask"))));
				fieldId = created.id();
				if (!bool(row.getOrDefault("active", true))) {
					config.updateField(fieldId, Map.of("active", false), true, null);
				}
			}
			else {
				Map<String, Object> patch = patchOf(current, row);
				patch.put("maskedDefault", bool(row.get("mask")));
				config.updateField(id, patch, true, null);
			}
			customAccess.apply(fieldId, str(row.getOrDefault("acc", "Everyone")), bool(row.getOrDefault("emp", true)), PROFILE.equals(area));
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("emp", bool(row.getOrDefault("emp", true)));
			m.put("acc", row.getOrDefault("acc", "Everyone"));
			meta.put(fieldId.toString(), m);
		}
		for (ConfigField f : existing.values()) {
			if (!kept.contains(f.getId())) {
				config.deleteField(f.getId(), true);
			}
		}
		return meta;
	}

	private List<ConfigField> customFields(String area) {
		Set<UUID> customSections = catalog.sections(area).stream()
				.filter(s -> CUSTOM_SECTION.equals(s.getSectionKey())).map(ConfigSection::getId).collect(Collectors.toSet());
		return catalog.fields(area).stream().filter(f -> customSections.contains(f.getSectionId())).toList();
	}

	/**
	 * A custom field that has values keeps its entity and key: moving it to another entity or renaming its key would orphan the values.
	 * Switch it off and add a new one instead.
	 */
	void checkCustomFieldLocks(List<Map<String, Object>> rows, List<com.ivoyant.hrms.common.web.ErrorItem> errors) {
		Map<UUID, ConfigField> existing = new HashMap<>();
		Map<UUID, String> areaOf = new HashMap<>();
		ENTITY_AREAS.values().forEach(area -> customFields(area).forEach(f -> {
			existing.put(f.getId(), f);
			areaOf.put(f.getId(), area);
		}));
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			ConfigField f;
			try {
				f = existing.get(UUID.fromString(String.valueOf(row.get("id"))));
			}
			catch (IllegalArgumentException ex) {
				continue;
			}
			if (f == null) {
				continue;
			}
			boolean hasData = config.usage(f.getId()).valueCount() > 0;
			String wantedArea = ENTITY_AREAS.get(String.valueOf(row.get("ent")));
			if (hasData && wantedArea != null && !wantedArea.equals(areaOf.get(f.getId()))) {
				errors.add(com.ivoyant.hrms.common.web.ErrorItem.cell("cfields", i, "ent", "ENTITY_LOCKED: " + f.getLabel() + " already has values, so it can't move to another entity"));
			}
			if (hasData && !blank(row.get("key")) && !f.getFieldKey().equalsIgnoreCase(str(row.get("key")).trim())) {
				errors.add(com.ivoyant.hrms.common.web.ErrorItem.cell("cfields", i, "key", "KEY_LOCKED: " + f.getLabel() + " already has values, so its key can't change"));
			}
		}
	}

	// ---- hardware / software: extra fields per asset type or software category ----

	private static String assetArea(String module) {
		return "software".equals(module) ? "asset_software" : "asset_hardware";
	}

	List<Map<String, Object>> loadAssetFields(String module) {
		String area = assetArea(module);
		Map<UUID, ConfigSection> sections = AreaCatalog.byId(catalog.sections(area), ConfigSection::getId);
		return catalog.fields(area).stream().filter(f -> f.getFieldType().hasValue() && sections.containsKey(f.getSectionId())).map(f -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", f.getId().toString());
			row.put("scope", sections.get(f.getSectionId()).getName());
			row.put("name", f.getLabel());
			row.put("ftype", uiType(f.getFieldType()));
			row.put("opts", String.join(", ", FieldValues.parseOptions(f.getOptions())));
			row.put("req", f.isRequired());
			row.put("active", f.isActive());
			return row;
		}).toList();
	}

	/** One section per asset type (hardware) or software category; a field removed while it has values is switched off, not deleted. */
	void saveAssetFields(String module, List<Map<String, Object>> rows) {
		String area = assetArea(module);
		Map<UUID, ConfigField> existing = AreaCatalog.byId(catalog.fields(area), ConfigField::getId);
		Set<UUID> kept = new java.util.HashSet<>();
		for (Map<String, Object> row : rows) {
			UUID id = UUID.fromString(row.get("id").toString());
			kept.add(id);
			ConfigField current = existing.get(id);
			if (current == null) {
				UUID sectionId = sectionFor(area, str(row.get("scope")));
				var created = config.createField(area, new CreateFieldRequest(sectionId, str(row.get("name")), type(row.get("ftype")), null,
						bool(row.get("req")), null, null, row.get("opts"), null, false));
				if (!bool(row.getOrDefault("active", true))) {
					config.updateField(created.id(), Map.of("active", false), true, null);
				}
			}
			else {
				config.updateField(id, patchOf(current, row), true, null);
			}
		}
		for (ConfigField f : existing.values()) {
			if (!kept.contains(f.getId()) && f.getFieldType().hasValue()) {
				config.deleteField(f.getId(), true);
			}
		}
	}

	private UUID sectionFor(String area, String name) {
		return catalog.sections(area).stream().filter(s -> s.getName().equalsIgnoreCase(name)).findFirst().map(ConfigSection::getId)
				.orElseGet(() -> config.createSection(area, new CreateSectionRequest(name, null, null)).id());
	}

	// ---- onboarding / portal: the employee profile sections shown to a new hire ----

	private static final Map<String, String> PORTAL_VALIDATORS = Map.of("PAN", "PAN", "IFSC", "IFSC", "Aadhaar", "AADHAAR", "Account number", "ACCOUNT");

	/** A view over the profile: every field of a section shown in the portal. */
	List<Map<String, Object>> loadPortal() {
		Map<UUID, ConfigSection> sections = AreaCatalog.byId(catalog.sections(PROFILE).stream().filter(ConfigSection::isShowInPortal).toList(),
				ConfigSection::getId);
		return catalog.fields(PROFILE).stream().filter(f -> f.getFieldType().hasValue() && sections.containsKey(f.getSectionId()) && f.getSource() == null)
				.map(f -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", f.getId().toString());
					row.put("section", sections.get(f.getSectionId()).getName());
					row.put("name", f.getLabel());
					String ui = uiType(f.getFieldType());
					if (f.getValidator() != null) {
						ui = PORTAL_VALIDATORS.entrySet().stream().filter(e -> e.getValue().equals(f.getValidator())).map(Map.Entry::getKey).findFirst().orElse(ui);
					}
					row.put("ftype", "Long text".equals(ui) ? "Textarea" : ui);
					row.put("opts", String.join(", ", FieldValues.parseOptions(f.getOptions())));
					row.put("req", f.isRequired());
					row.put("active", f.isActive());
					row.put("key", f.getFieldKey());
					return row;
				}).toList();
	}

	/**
	 * Edits write straight to the profile fields; a new section name creates a profile section shown in the portal. Fields that
	 * fall out of the list are switched off in the portal's view only when they are not built in.
	 */
	void savePortal(List<Map<String, Object>> rows) {
		Map<UUID, ConfigField> existing = AreaCatalog.byId(catalog.fields(PROFILE), ConfigField::getId);
		Set<UUID> kept = new java.util.HashSet<>();
		for (Map<String, Object> row : rows) {
			UUID id = UUID.fromString(row.get("id").toString());
			kept.add(id);
			ConfigField current = existing.get(id);
			String uiType = str(row.get("ftype"));
			String validator = PORTAL_VALIDATORS.get(uiType);
			UUID fieldId = id;
			if (current == null) {
				UUID sectionId = portalSection(str(row.get("section")));
				var created = config.createField(PROFILE, new CreateFieldRequest(sectionId, str(row.get("name")), validator != null ? FieldType.TEXT : type(uiType),
						blank(row.get("key")) ? null : str(row.get("key")), bool(row.get("req")), null, null, row.get("opts"), null, false));
				fieldId = created.id();
				if (!bool(row.getOrDefault("active", true))) {
					config.updateField(fieldId, Map.of("active", false), true, null);
				}
			}
			else {
				Map<String, Object> patch = patchOf(current, row);
				if (validator != null) {
					patch.remove("type");
				}
				config.updateField(id, patch, true, null);
			}
			ConfigField f = fieldRepo.findById(fieldId).orElseThrow();
			f.setValidator(validator);
			fieldRepo.saveAndFlush(f);
		}
		for (ConfigField f : existing.values()) {
			boolean inPortal = catalog.sections(PROFILE).stream().anyMatch(s -> s.getId().equals(f.getSectionId()) && s.isShowInPortal());
			if (inPortal && !kept.contains(f.getId()) && !f.isSystem() && f.getFieldType().hasValue()) {
				config.deleteField(f.getId(), true);
			}
		}
	}

	private UUID portalSection(String name) {
		String n = name.isBlank() ? "Additional details" : name;
		ConfigSection found = catalog.sections(PROFILE).stream().filter(s -> s.getName().equalsIgnoreCase(n) && !s.isLocked()).findFirst().orElse(null);
		if (found == null) {
			UUID id = config.createSection(PROFILE, new CreateSectionRequest(n, null, null)).id();
			config.updateSection(id, Map.of("showInPortal", true), null);
			return id;
		}
		if (!found.isShowInPortal()) {
			config.updateSection(found.getId(), Map.of("showInPortal", true), null);
		}
		return found.getId();
	}

	// ---- shared ----

	/** Only what changed: system fields keep their type, and a changed label or options is passed through. */
	private Map<String, Object> patchOf(ConfigField current, Map<String, Object> row) {
		Map<String, Object> patch = new HashMap<>();
		patch.put("label", str(row.get("name")));
		patch.put("required", bool(row.get("req")));
		patch.put("active", bool(row.getOrDefault("active", true)));
		if (!current.isSystem()) {
			FieldType wanted = type(row.get("ftype"));
			if (wanted != current.getFieldType()) {
				patch.put("type", wanted.name());
			}
		}
		if (row.containsKey("opts")) {
			patch.put("options", row.get("opts"));
		}
		return patch;
	}

	private static ConfigSection sectionByName(List<ConfigSection> sections, String name) {
		return sections.stream().filter(s -> s.getName().equalsIgnoreCase(name)).findFirst()
				.orElse(sections.get(0));
	}

	static FieldType type(Object ui) {
		FieldType t = ui == null ? null : TYPES.get(ui.toString());
		return t == null ? FieldType.TEXT : t;
	}

	static String uiType(FieldType t) {
		return switch (t) {
			case TEXT -> "Text";
			case TEXTAREA -> "Long text";
			case NUMBER, CURRENCY -> "Number";
			case DATE -> "Date";
			case SELECT -> "Dropdown";
			case MULTISELECT -> "Multi-select";
			case BOOLEAN -> "Yes / No";
			case EMAIL -> "Email";
			case PHONE -> "Phone";
			case DEPARTMENT -> "Department";
			case EMPLOYEE -> "Employee";
			case LOCATION -> "Location";
			case EMPLOYMENT_TYPE -> "Employment type";
			case FILE, HEADING -> t.name().toLowerCase(Locale.ROOT);
		};
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	private static boolean blank(Object o) {
		return o == null || o.toString().isBlank();
	}

	private static boolean bool(Object o) {
		return o != null && Boolean.parseBoolean(o.toString());
	}
}
