package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Who changed what on a profile, from the audit trail; values follow what the viewer may see of each field today. */
@Service
public class HistoryService {

	private static final int PAGE = 50;

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final EmployeeRepository employees;
	private final FieldConfig fieldConfig;
	private final AuthzService authz;
	private final ObjectMapper json;

	HistoryService(JdbcClient jdbc, CurrentUser currentUser, EmployeeActions actions, EmployeeRepository employees, FieldConfig fieldConfig, AuthzService authz,
			ObjectMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.actions = actions;
		this.employees = employees;
		this.fieldConfig = fieldConfig;
		this.authz = authz;
		this.json = json;
	}

	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public Map<String, Object> list(UUID id, String field, OffsetDateTime from, OffsetDateTime to, int page) {
		if (employees.findByIdAndOrgId(id, currentUser.orgId()).isEmpty()) {
			throw new NotFoundException("Employee " + id + " not found");
		}
		actions.require("view_history", id);
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, id);
		Map<String, String> labels = fieldConfig.activeFields(EmployeeFields.AREA).stream().collect(Collectors.toMap(FieldDefinition::key, FieldDefinition::label, (a, b) -> a));
		boolean superAdmin = authz.isSuperAdmin();
		List<Map<String, Object>> entries = new ArrayList<>();
		List<Map<String, Object>> rows = jdbc.sql("""
				select a.id, a.actor_id, p.display_name, a.action, a.before::text, a.after::text, a.at
				from audit_log a left join employee p on p.id = a.actor_id
				where a.module = 'employee' and a.entity = 'Employee' and a.entity_id = :e
				  and (cast(:from as timestamptz) is null or a.at >= :from) and (cast(:to as timestamptz) is null or a.at <= :to)
				order by a.at desc limit 2000""").param("e", id.toString()).param("from", from, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
				.param("to", to, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("actorId", rs.getObject(2, UUID.class));
					m.put("actorName", rs.getString(3));
					m.put("action", rs.getString(4));
					m.put("before", rs.getString(5));
					m.put("after", rs.getString(6));
					m.put("at", rs.getObject(7, OffsetDateTime.class));
					return m;
				}).list();
		for (Map<String, Object> r : rows) {
			String action = (String) r.get("action");
			Object actor = r.get("actorId") == null ? null : Map.of("id", r.get("actorId"), "name", String.valueOf(r.get("actorName")));
			if ("READ_SENSITIVE".equals(action) && !superAdmin) {
				continue;
			}
			if ("UPDATE".equals(action)) {
				Map<String, Object> before = parse((String) r.get("before"));
				Map<String, Object> after = parse((String) r.get("after"));
				for (String key : after.keySet()) {
					if (java.util.Objects.equals(before.get(key), after.get(key))) {
						continue;
					}
					if (field != null && !field.isBlank() && !field.equals(key)) {
						continue;
					}
					entries.add(fieldEntry(r, actor, key, labels.getOrDefault(key, key), levels.getOrDefault(key, FieldAccess.HIDDEN), before.get(key), after.get(key)));
				}
				continue;
			}
			if (field != null && !field.isBlank()) {
				continue;
			}
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("at", r.get("at"));
			e.put("actor", actor);
			e.put("kind", kind(action));
			e.put("action", action);
			e.put("note", r.get("after") == null ? null : trim((String) r.get("after")));
			entries.add(e);
		}
		int p = Math.max(0, page);
		int fromIdx = Math.min(entries.size(), p * PAGE);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", entries.subList(fromIdx, Math.min(entries.size(), fromIdx + PAGE)));
		out.put("page", p);
		out.put("total", entries.size());
		return out;
	}

	private Map<String, Object> fieldEntry(Map<String, Object> r, Object actor, String key, String label, FieldAccess level, Object from, Object to) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("at", r.get("at"));
		e.put("actor", actor);
		e.put("kind", "FIELD_EDIT");
		e.put("field", key);
		e.put("label", label);
		if (level == FieldAccess.HIDDEN) {
			e.put("note", "Hidden field changed");
		}
		else if (level == FieldAccess.MASKED) {
			e.put("from", Masking.mask(from));
			e.put("to", Masking.mask(to));
		}
		else {
			e.put("from", from);
			e.put("to", to);
		}
		return e;
	}

	private static String kind(String action) {
		if (action.startsWith("JOB_CHANGE") || action.equals("REHOME")) {
			return "JOB_CHANGE";
		}
		if (action.startsWith("EXIT")) {
			return "EXIT";
		}
		if (action.startsWith("PHOTO")) {
			return "PHOTO";
		}
		if (action.startsWith("DOCUMENT") || action.startsWith("PREV_EMPLOYMENT")) {
			return "DOCUMENT";
		}
		if (action.startsWith("CHANGE_REQUEST")) {
			return "CHANGE_REQUEST";
		}
		return "STATUS";
	}

	private static String trim(String s) {
		return s.length() > 200 ? s.substring(0, 200) : s;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parse(String s) {
		if (s == null || s.isBlank() || !s.trim().startsWith("{")) {
			return Map.of();
		}
		try {
			return json.readValue(s, Map.class);
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}
}
