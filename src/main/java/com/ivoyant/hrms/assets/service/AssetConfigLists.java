package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.forms.JdbcTableList;
import com.ivoyant.hrms.common.forms.JdbcTableList.Col;
import com.ivoyant.hrms.common.forms.JdbcTableList.Rules;
import com.ivoyant.hrms.common.forms.TableBackedList;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Settings → Configuration → Asset configuration, hardware and software: the lists the editor saves, each in its own table. Whole-list
 * rules (an assignable status, one ID format per ownership, a category for every hardware type) are checked together with the cell rules.
 */
@Configuration
class AssetConfigLists {

	private static final String EVENT_READ = """
			case t.event when 'WARRANTY_END' then 'Warranty ends' when 'INSURANCE_END' then 'Insurance ends' when 'LEASE_END' then 'Lease ends'
			    when 'CLIENT_RETURN' then 'Client return due' when 'MAX_AGE' then 'Reaches max age' when 'RENEWAL' then 'Renewal date'
			    when 'CONTRACT_END' then 'Contract ends' else t.event end""";
	private static final String EVENT_WRITE = """
			case lower(cast(:event as text)) when 'warranty ends' then 'WARRANTY_END' when 'insurance ends' then 'INSURANCE_END'
			    when 'lease ends' then 'LEASE_END' when 'client return due' then 'CLIENT_RETURN' when 'reaches max age' then 'MAX_AGE'
			    when 'renewal date' then 'RENEWAL' when 'contract ends' then 'CONTRACT_END'
			    else upper(replace(cast(:event as text), ' ', '_')) end""";
	private static final Set<String> EVENTS = Set.of("WARRANTY_END", "INSURANCE_END", "LEASE_END", "CLIENT_RETURN", "MAX_AGE", "RENEWAL", "CONTRACT_END");

	private final JdbcClient jdbc;
	private final CurrentUser user;

	AssetConfigLists(JdbcClient jdbc, CurrentUser user) {
		this.jdbc = jdbc;
		this.user = user;
	}

	private TableBackedList list(String module, String key, int order, String table, String kind, String orderBy, String nameCol, List<Col> cols, Rules rules) {
		return new JdbcTableList(jdbc, user, module, key, order, table, kind == null ? null : "t.kind = '" + kind + "'",
				kind == null ? Map.of() : Map.of("kind", "'" + kind + "'"), orderBy, nameCol, cols, rules);
	}

	// ================================================================== hardware

	@Bean
	TableBackedList hardwareStatuses() {
		return statuses("hardware", "HARDWARE");
	}

	@Bean
	TableBackedList hardwareCategories() {
		return categories("hardware", "HARDWARE");
	}

	@Bean
	TableBackedList hardwareOwnership() {
		return list("hardware", "ownership", 20, "asset_ownership", null, "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.sql("fields", "capture_fields",
						"coalesce(array_to_string(array(select jsonb_array_elements_text(t.capture_fields)), ', '), '')",
						"to_jsonb(array(select trim(x) from unnest(string_to_array(coalesce(cast(:fields as text), ''), ',')) x where trim(x) <> ''))"),
						Col.bool("returnReq", "return_required"), Col.bool("active", "active")),
				(rows, all, errors) -> {
					uniqueNames("ownership", rows, errors);
					formatsPerOwnership(rows, all.getOrDefault("idformat", List.of()), errors);
				});
	}

	@Bean
	TableBackedList hardwareTypes() {
		return list("hardware", "types", 30, "asset_type", "HARDWARE", "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.uuid("cat", "category_id"), Col.text("icon", "icon"), Col.integer("age", "max_age_years"),
						Col.bool("active", "active")),
				(rows, all, errors) -> {
					uniqueNames("types", rows, errors);
					for (int i = 0; i < rows.size(); i++) {
						Map<String, Object> r = rows.get(i);
						if (blank(r.get("cat"))) {
							errors.add(ErrorItem.cell("types", i, "cat", "Category is required"));
						}
						if (!blank(r.get("age"))) {
							try {
								double age = Double.parseDouble(r.get("age").toString());
								if (age < 1 || age > 20) {
									errors.add(ErrorItem.cell("types", i, "age", "Max age must be between 1 and 20 years"));
								}
							}
							catch (NumberFormatException ex) {
								// already reported as "must be a number"
							}
						}
					}
				});
	}

	@Bean
	TableBackedList hardwareIdFormats() {
		return list("hardware", "idformat", 40, "asset_id_format", "HARDWARE", "t.sort, t.fmt", "t.fmt",
				List.of(Col.sql("name", "ownership_id", "(select o.name from asset_ownership o where o.id = t.ownership_id)",
						"(select o.id from asset_ownership o where o.org_id = :org and lower(o.name) = lower(cast(:name as text)) limit 1)"),
						Col.text("fmt", "fmt"), Col.bool("active", "active")),
				(rows, all, errors) -> formatsPerOwnership(all.getOrDefault("ownership", List.of()), rows, errors));
	}

	@Bean
	TableBackedList hardwareAlerts() {
		return alerts("hardware", "HARDWARE");
	}

	// ================================================================== software

	@Bean
	TableBackedList softwareStatuses() {
		return statuses("software", "SOFTWARE");
	}

	@Bean
	TableBackedList softwareCategories() {
		return categories("software", "SOFTWARE");
	}

	@Bean
	TableBackedList softwareTypes() {
		return list("software", "types", 30, "asset_type", "SOFTWARE", "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.text("billing", "billing"), Col.text("unit", "unit"), Col.bool("active", "active")),
				(rows, all, errors) -> uniqueNames("types", rows, errors));
	}

	@Bean
	TableBackedList softwareIdFormats() {
		return list("software", "idformat", 40, "asset_id_format", "SOFTWARE", "t.sort, t.fmt", "t.fmt",
				List.of(Col.sql("name", "license_type_id", "(select a.name from asset_type a where a.id = t.license_type_id)",
						"(select a.id from asset_type a where a.org_id = :org and a.kind = 'SOFTWARE' and lower(a.name) = lower(cast(:name as text)) limit 1)"),
						Col.text("fmt", "fmt"), Col.bool("active", "active")),
				null);
	}

	@Bean
	TableBackedList softwareAlerts() {
		return alerts("software", "SOFTWARE");
	}

	// ================================================================== shared shapes

	private TableBackedList statuses(String module, String kind) {
		return list(module, "statuses", 10, "asset_status", kind, "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.integer("color", "color"), Col.bool("assignable", "assignable"), Col.bool("active", "active")),
				(rows, all, errors) -> {
					uniqueNames("statuses", rows, errors);
					boolean ok = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("assignable")) && !Boolean.FALSE.equals(r.get("active")));
					if (!ok) {
						errors.add(ErrorItem.cell("statuses", 0, "assignable", "NEED_ASSIGNABLE: keep at least one active status that can be assigned"));
					}
				});
	}

	private TableBackedList categories(String module, String kind) {
		return list(module, "categories", 20, "asset_category", kind, "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.text("icon", "icon"), Col.bool("active", "active")),
				(rows, all, errors) -> {
					uniqueNames("categories", rows, errors);
					// a category can't be switched off while an active type still uses it (hardware types point at categories)
					List<Map<String, Object>> types = all.getOrDefault("types", List.of());
					for (int i = 0; i < rows.size(); i++) {
						Map<String, Object> c = rows.get(i);
						if (Boolean.FALSE.equals(c.get("active")) && types.stream().anyMatch(t -> !Boolean.FALSE.equals(t.get("active"))
								&& String.valueOf(c.get("id")).equals(String.valueOf(t.get("cat"))))) {
							errors.add(ErrorItem.cell("categories", i, "active", "CATEGORY_IN_USE: " + c.get("name") + " still has active types"));
						}
					}
				});
	}

	private TableBackedList alerts(String module, String kind) {
		return list(module, "alerts", 50, "asset_alert_rule", kind, "t.sort, t.name", "t.name",
				List.of(Col.text("name", "name"), Col.sql("days", "days_before", "cast(t.days_before as text)", "cast(cast(:days as text) as int)"),
						Col.sql("event", "event", EVENT_READ, EVENT_WRITE), Col.bool("inapp", "in_app"), Col.bool("email", "email")),
				(rows, all, errors) -> {
					uniqueNames("alerts", rows, errors);
					for (int i = 0; i < rows.size(); i++) {
						String code = String.valueOf(rows.get(i).get("event")).toUpperCase(Locale.ROOT).replace(' ', '_');
						Set<String> known = new HashSet<>(EVENTS);
						known.addAll(List.of("WARRANTY_ENDS", "INSURANCE_ENDS", "LEASE_ENDS", "CLIENT_RETURN_DUE", "REACHES_MAX_AGE", "RENEWAL_DATE", "CONTRACT_ENDS"));
						if (blank(rows.get(i).get("event")) || !known.contains(code)) {
							errors.add(ErrorItem.cell("alerts", i, "event", "Pick what the alert is about: warranty ends, insurance ends, lease ends, client return due, reaches max age, renewal date or contract ends"));
						}
					}
				});
	}

	// ================================================================== helpers

	private static boolean blank(Object o) {
		return o == null || o.toString().isBlank();
	}

	/** Every active ownership has exactly one active ID format (reported on the ownership row, from either list). */
	private static void formatsPerOwnership(List<Map<String, Object>> owners, List<Map<String, Object>> formats, List<ErrorItem> errors) {
		for (int i = 0; i < owners.size(); i++) {
			Map<String, Object> o = owners.get(i);
			if (Boolean.FALSE.equals(o.get("active"))) {
				continue;
			}
			long n = formats.stream().filter(f -> !Boolean.FALSE.equals(f.get("active")) && String.valueOf(o.get("name")).equalsIgnoreCase(String.valueOf(f.get("name")))).count();
			if (n != 1) {
				errors.add(ErrorItem.cell("ownership", i, "name", "FORMAT_MISSING: " + o.get("name") + " needs exactly one active ID format"));
			}
		}
	}

	/** Names are unique ignoring case within a list. */
	private static void uniqueNames(String listKey, List<Map<String, Object>> rows, List<ErrorItem> errors) {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < rows.size(); i++) {
			Object n = rows.get(i).get("name");
			if (!blank(n) && !seen.add(n.toString().trim().toLowerCase(Locale.ROOT))) {
				errors.add(ErrorItem.cell(listKey, i, "name", "NAME_TAKEN: " + n + " already exists"));
			}
		}
	}

	@SuppressWarnings("unused")
	private static List<String> none() {
		return new ArrayList<>();
	}
}
