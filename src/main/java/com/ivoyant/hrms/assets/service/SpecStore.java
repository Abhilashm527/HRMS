package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.FieldValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The extra fields of an asset type, a software category or a request (Settings → Fields &amp; forms). Values live in
 * {@code config_field_value}, keyed by the record; access is the assets module permission, not the per-role field matrix.
 */
@Component
class SpecStore {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final FieldConfig fieldConfig;
	private final ObjectMapper json;

	SpecStore(JdbcClient jdbc, CurrentUser currentUser, FieldConfig fieldConfig, ObjectMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.fieldConfig = fieldConfig;
		this.json = json;
	}

	/** Active fields of the area that belong to the section ({@code sectionKey} null = the whole area). */
	List<FieldDefinition> fields(String areaKey, String sectionKey) {
		return fieldConfig.activeFields(areaKey).stream().filter(d -> d.type().hasValue() && (sectionKey == null || sectionKey.equals(d.sectionKey()))).toList();
	}

	/** The current values as {@code [{key, label, v}]}; only fields that have a value. */
	List<Map<String, Object>> read(String areaKey, UUID recordId, String sectionKey) {
		Map<String, FieldDefinition> defs = fields(areaKey, sectionKey).stream().collect(Collectors.toMap(FieldDefinition::key, d -> d, (a, b) -> a));
		List<Map<String, Object>> out = new ArrayList<>();
		jdbc.sql("""
				select f.key, f.label, v.value::text from config_field_value v join config_field f on f.id = v.field_id
				where v.org_id = :org and v.area_key = :a and v.record_id = :r order by f.sort""").param("org", currentUser.orgId()).param("a", areaKey)
				.param("r", recordId).query((rs, i) -> {
					if (defs.containsKey(rs.getString(1)) && rs.getString(3) != null) {
						Object v = json.readValue(rs.getString(3), Object.class);
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("key", rs.getString(1));
						m.put("k", rs.getString(2));
						m.put("v", v);
						out.add(m);
					}
					return 0;
				}).list();
		return out;
	}

	/**
	 * Validates (types, options, required ones of the section) and stores the values. Keys may be field keys or field ids.
	 * {@code merge} keeps values that are not sent; otherwise the record's values are replaced.
	 */
	void write(String areaKey, UUID recordId, String sectionKey, Map<String, Object> values, boolean merge) {
		List<FieldDefinition> defs = fields(areaKey, sectionKey);
		Map<String, FieldDefinition> byKey = defs.stream().collect(Collectors.toMap(FieldDefinition::key, d -> d, (a, b) -> a));
		Map<String, FieldDefinition> byId = defs.stream().collect(Collectors.toMap(d -> d.id().toString(), d -> d, (a, b) -> a));
		Map<String, Object> clean = new LinkedHashMap<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (Map.Entry<String, Object> e : (values == null ? Map.<String, Object>of() : values).entrySet()) {
			FieldDefinition d = byKey.containsKey(e.getKey()) ? byKey.get(e.getKey()) : byId.get(e.getKey());
			if (d == null) {
				continue;
			}
			if (FieldValues.isBlank(e.getValue())) {
				clean.put(d.key(), null);
				continue;
			}
			try {
				clean.put(d.key(), FieldValues.normalize(d, e.getValue()));
			}
			catch (FieldValues.Invalid ex) {
				errors.add(ErrorItem.field(d.key(), "INVALID", ex.getMessage()));
			}
		}
		if (merge) {
			read(areaKey, recordId, sectionKey).forEach(m -> clean.putIfAbsent((String) m.get("key"), m.get("v")));
		}
		for (FieldDefinition d : defs) {
			if (d.required() && clean.get(d.key()) == null) {
				errors.add(ErrorItem.field(d.key(), "SPEC_REQUIRED", d.label() + " is required"));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		for (FieldDefinition d : defs) {
			Object v = clean.get(d.key());
			if (v == null) {
				jdbc.sql("delete from config_field_value where org_id = :org and area_key = :a and record_id = :r and field_id = :f").param("org", currentUser.orgId())
						.param("a", areaKey).param("r", recordId).param("f", d.id()).update();
			}
			else {
				jdbc.sql("""
						insert into config_field_value (org_id, area_key, record_id, field_id, value, updated_by) values (:org, :a, :r, :f, :v::jsonb, :by)
						on conflict (org_id, area_key, record_id, field_id) do update set value = :v::jsonb, updated_at = now(), updated_by = :by, version = config_field_value.version + 1""")
						.param("org", currentUser.orgId()).param("a", areaKey).param("r", recordId).param("f", d.id()).param("v", json.writeValueAsString(v))
						.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
			}
		}
	}
}
