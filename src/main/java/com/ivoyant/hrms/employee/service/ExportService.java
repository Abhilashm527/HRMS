package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.util.Csv;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** CSV export of the directory: only the columns the caller can see; a big export is saved and fetched by id. */
@Service
public class ExportService {

	static final int SYNC_ROWS = 2000;
	static final int MAX_ROWS = 10000;
	private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("code", "Employee ID"), Map.entry("name", "Name"), Map.entry("title", "Title"),
			Map.entry("position", "Designation"), Map.entry("department", "Department"), Map.entry("location", "Location"), Map.entry("manager", "Reports to"),
			Map.entry("doj", "Joined"), Map.entry("email", "Work email"), Map.entry("phone", "Phone"), Map.entry("status", "Status"));

	public record Result(byte[] csv, UUID jobId, List<String> dropped, int rows) {
	}

	private final DirectoryService directory;
	private final EmployeeActions actions;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final FileStore store;
	private final ObjectMapper json;

	ExportService(DirectoryService directory, EmployeeActions actions, JdbcClient jdbc, CurrentUser currentUser, FileStore store, ObjectMapper json) {
		this.directory = directory;
		this.actions = actions;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.store = store;
		this.json = json;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Result export(Map<String, Object> body) {
		actions.require("export_employees", null);
		DirectoryService.Query q = new DirectoryService.Query(str(body.get("q")), uuid(body.get("departmentId")), uuid(body.get("locationId")), str(body.get("status")),
				uuid(body.get("managerId")), Boolean.TRUE.equals(body.get("includeExited")), null, null, false, 0, 0, null);
		Set<String> visible = directory.visibleColumns();
		List<String> requested = body.get("columns") instanceof List<?> l ? l.stream().map(Object::toString).toList() : new ArrayList<>(LABELS.keySet());
		List<String> columns = new ArrayList<>(List.of("code", "name"));
		List<String> dropped = new ArrayList<>();
		for (String c : requested) {
			if (c.equals("code") || c.equals("name")) {
				continue;
			}
			if (visible.contains(c)) {
				columns.add(c);
			}
			else {
				dropped.add(c);
			}
		}
		List<Map<String, Object>> rows = directory.rows(q, MAX_ROWS + 1);
		if (rows.size() > MAX_ROWS) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "TOO_MANY_ROWS", "More than " + MAX_ROWS + " people match. Narrow the filters.")));
		}
		StringBuilder sb = new StringBuilder();
		sb.append(String.join(",", columns.stream().map(c -> Csv.cell(LABELS.getOrDefault(c, c))).toList())).append('\n');
		for (Map<String, Object> r : rows) {
			List<String> cells = new ArrayList<>();
			for (String c : columns) {
				cells.add(Csv.cell(flat(r.get(c))));
			}
			sb.append(String.join(",", cells)).append('\n');
		}
		byte[] csv = sb.toString().getBytes(StandardCharsets.UTF_8);
		if (rows.size() <= SYNC_ROWS) {
			return new Result(csv, null, dropped, rows.size());
		}
		UUID jobId = UUID.randomUUID();
		String key = currentUser.orgId() + "/exports/" + jobId + ".csv";
		try {
			store.put(key, csv);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The export could not be saved. Try again.");
		}
		jdbc.sql("""
				insert into export_job (id, org_id, requested_by, kind, params, state, storage_key, rows, finished_at)
				values (:id, :org, :by, 'EMPLOYEES', :p::jsonb, 'DONE', :k, :n, now())""").param("id", jobId).param("org", currentUser.orgId())
				.param("by", currentUser.requireEmployeeId()).param("p", json.writeValueAsString(body)).param("k", key).param("n", rows.size()).update();
		return new Result(null, jobId, dropped, rows.size());
	}

	@Transactional(readOnly = true)
	public Map<String, Object> job(UUID jobId) {
		Map<String, Object> m = jdbc.sql("select state, rows, error from export_job where id = :id and requested_by = :by and org_id = :org").param("id", jobId)
				.param("by", currentUser.requireEmployeeId()).param("org", currentUser.orgId()).query((rs, i) -> {
					Map<String, Object> o = new LinkedHashMap<>();
					o.put("state", rs.getString(1));
					o.put("rows", rs.getObject(2));
					o.put("error", rs.getString(3));
					return o;
				}).optional().orElseThrow(() -> new NotFoundException("Export " + jobId + " not found"));
		if ("DONE".equals(m.get("state"))) {
			m.put("downloadUrl", "/api/v1/exports/" + jobId + "/file");
		}
		return m;
	}

	@Transactional(readOnly = true)
	public byte[] file(UUID jobId) {
		String key = jdbc.sql("select storage_key from export_job where id = :id and requested_by = :by and org_id = :org and state = 'DONE'").param("id", jobId)
				.param("by", currentUser.requireEmployeeId()).param("org", currentUser.orgId()).query(String.class).optional()
				.orElseThrow(() -> new NotFoundException("Export " + jobId + " not found"));
		try {
			return store.get(key);
		}
		catch (IOException ex) {
			throw new NotFoundException("The export is no longer available");
		}
	}

	@SuppressWarnings("unchecked")
	private static String flat(Object o) {
		if (o instanceof Map<?, ?> m) {
			return String.valueOf(m.get("name"));
		}
		return o == null ? "" : o.toString();
	}

	private static String str(Object o) {
		return o == null || o.toString().isBlank() ? null : o.toString();
	}

	private static UUID uuid(Object o) {
		return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString());
	}
}
