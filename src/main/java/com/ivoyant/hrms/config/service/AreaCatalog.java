package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.config.ConfigAreas;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.VisibilityRule;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.repo.ConfigAreaRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldRepository;
import com.ivoyant.hrms.config.repo.ConfigSectionRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads areas, sections and fields of the caller's org and maps them to the shared definitions. */
@Component
public class AreaCatalog implements ConfigAreas {

	public static final String PROFILE = "employee_profile";
	public static final String ONBOARDING = "onboarding";

	private static final Set<FieldType> BASE_TYPES = EnumSet.of(FieldType.TEXT, FieldType.TEXTAREA, FieldType.NUMBER,
			FieldType.DATE, FieldType.EMAIL, FieldType.PHONE, FieldType.SELECT, FieldType.MULTISELECT, FieldType.BOOLEAN, FieldType.FILE);

	private final ConfigAreaRepository areas;
	private final ConfigSectionRepository sections;
	private final ConfigFieldRepository fields;
	private final CurrentUser currentUser;
	private final JdbcTemplate jdbc;

	public AreaCatalog(ConfigAreaRepository areas, ConfigSectionRepository sections, ConfigFieldRepository fields,
			CurrentUser currentUser, JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.areas = areas;
		this.sections = sections;
		this.fields = fields;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean exists(String areaKey) {
		return areas.existsById(areaKey);
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> keys() {
		return areas.findAllByOrderBySortAscNameAsc().stream().map(ConfigArea::getAreaKey).toList();
	}

	/** Every area, in menu order. */
	public List<ConfigArea> areaList() {
		return areas.findAllByOrderBySortAscNameAsc();
	}

	public ConfigArea area(String areaKey) {
		return areas.findById(areaKey).orElseThrow(() -> new NotFoundException("Area '" + areaKey + "' not found"));
	}

	/** Field types the area's forms offer (config_area_type; the base set when the area has no rows). */
	public Set<FieldType> allowedTypes(String areaKey) {
		Set<FieldType> types = EnumSet.noneOf(FieldType.class);
		for (String t : jdbc.queryForList("select type from config_area_type where area_key = ?", String.class, areaKey)) {
			try {
				types.add(FieldType.valueOf(t));
			} catch (IllegalArgumentException ignored) {
				// unknown type name in the table: skip
			}
		}
		return types.isEmpty() ? EnumSet.copyOf(BASE_TYPES) : types;
	}

	/** All sections of the area, in display order (active or not, locked or not). */
	public List<ConfigSection> sections(String areaKey) {
		return sections.findByOrgIdAndAreaKeyOrderBySortAscNameAsc(currentUser.orgId(), areaKey).stream()
				.filter(s -> s.getDeletedAt() == null).toList();
	}

	/** All fields of the area (active or not) in display order: by section, then field. */
	public List<ConfigField> fields(String areaKey) {
		Map<UUID, Integer> order = sectionOrder(sections(areaKey));
		return fields.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(currentUser.orgId(), areaKey).stream()
				.filter(f -> f.getDeletedAt() == null)
				.sorted(Comparator.comparingInt(f -> order.getOrDefault(f.getSectionId(), Integer.MAX_VALUE)))
				.toList();
	}

	/** Active fields of active sections, in display order. */
	public List<ConfigField> activeFields(String areaKey) {
		Set<UUID> activeSections = sections(areaKey).stream().filter(ConfigSection::isActive)
				.map(ConfigSection::getId).collect(Collectors.toSet());
		return fields(areaKey).stream().filter(f -> f.isActive() && activeSections.contains(f.getSectionId())).toList();
	}

	public List<FieldDefinition> definitions(String areaKey) {
		Map<UUID, String> keys = sections(areaKey).stream()
				.collect(Collectors.toMap(ConfigSection::getId, ConfigSection::getSectionKey));
		return activeFields(areaKey).stream().map(f -> definition(f, keys.get(f.getSectionId()))).toList();
	}

	public static FieldDefinition definition(ConfigField f, String sectionKey) {
		return new FieldDefinition(f.getId(), f.getAreaKey(), f.getFieldKey(), f.getLabel(), f.getFieldType(),
				f.isRequired(), f.isSystem(), f.getDefaultValue(), FieldValues.parseOptions(f.getOptions()),
				f.getHelpText(), VisibilityRule.fromJson(f.getVisibilityRule()), f.getSort(), f.getSectionId(),
				sectionKey, f.isMaskedDefault(), f.getSource(), f.getValidator());
	}

	/** Fields whose visibility condition is false for these values. */
	public static Set<String> hidden(List<FieldDefinition> defs, Map<String, Object> values) {
		Map<String, Object> byKey = new HashMap<>(values);
		return defs.stream()
				.filter(d -> d.visibilityRule() != null && !d.visibilityRule().visible(byKey::get))
				.map(FieldDefinition::key)
				.collect(Collectors.toSet());
	}

	private static Map<UUID, Integer> sectionOrder(List<ConfigSection> list) {
		Map<UUID, Integer> order = new HashMap<>();
		for (int i = 0; i < list.size(); i++) {
			order.put(list.get(i).getId(), i);
		}
		return order;
	}

	public static <T> Map<UUID, T> byId(List<T> list, Function<T, UUID> id) {
		return list.stream().collect(Collectors.toMap(id, Function.identity()));
	}
}
