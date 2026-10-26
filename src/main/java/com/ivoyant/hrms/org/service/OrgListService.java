package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.org.OrgLists;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the org-backed lookup lists straight from their tables, driven by a column map per list. */
@Service
class OrgListService implements OrgLists {

	private enum T { TEXT, INT, NUM, BOOL, UUID }

	/** {@code ui} is the settings UI's column key, {@code db} the table column; {@code self} points at the same table. */
	private record Col(String ui, String db, T type, boolean self) {
	}

	private record Def(String table, String order, List<Col> cols) {
	}

	private static Col c(String ui, String db, T type) {
		return new Col(ui, db, type, false);
	}

	private static Col self(String ui, String db) {
		return new Col(ui, db, T.UUID, true);
	}

	private static final Map<String, Def> DEFS = Map.of(
			"departments", new Def("department", "name", List.of(c("name", "name", T.TEXT), c("code", "code", T.TEXT),
					c("head", "head_employee_id", T.UUID), self("parent", "parent_id"), c("cc", "cost_centre", T.TEXT),
					c("loc", "default_location_id", T.UUID), c("active", "active", T.BOOL))),
			"designations", new Def("designation", "level nulls last, title", List.of(c("name", "title", T.TEXT),
					c("grade", "code", T.TEXT), c("lvl", "level", T.INT), c("track", "track", T.TEXT),
					c("bmin", "band_min", T.NUM), c("bmax", "band_max", T.NUM), self("rep", "reports_to_id"),
					c("minExp", "min_exp", T.NUM), c("appr", "approves", T.BOOL), c("jl", "job_level_id", T.UUID),
					c("active", "active", T.BOOL))),
			"titles", new Def("job_title", "name", List.of(c("name", "name", T.TEXT), c("code", "code", T.TEXT),
					c("pos", "designation_id", T.UUID), c("dept", "department_id", T.UUID),
					c("desc", "description", T.TEXT), c("active", "active", T.BOOL))),
			"levels", new Def("job_level", "code", List.of(c("code", "code", T.TEXT), c("name", "name", T.TEXT),
					c("min", "min_exp", T.NUM), c("max", "max_exp", T.NUM), c("appr", "approves", T.BOOL),
					c("active", "active", T.BOOL))),
			"teams", new Def("team", "name", List.of(c("name", "name", T.TEXT), c("dept", "department_id", T.UUID),
					c("lead", "lead_employee_id", T.UUID), c("active", "active", T.BOOL))),
			"projects", new Def("project", "name", List.of(c("name", "name", T.TEXT), c("code", "code", T.TEXT),
					c("client", "client", T.TEXT), c("owner", "owner_employee_id", T.UUID),
					c("status", "status", T.TEXT), c("active", "active", T.BOOL))),
			"emptypes", new Def("employment_type", "name", List.of(c("name", "name", T.TEXT), c("code", "code", T.TEXT),
					c("prob", "probation_months", T.INT), c("notice", "notice_days", T.INT),
					c("leave", "leave_eligible", T.BOOL), c("payroll", "payroll_eligible", T.BOOL),
					c("active", "active", T.BOOL))));

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final DepartmentService departments;
	private final DesignationService positions;
	private final TitleService titles;
	private final TeamService teams;
	private final ProjectService projects;
	private final EmploymentTypeService employmentTypes;

	OrgListService(JdbcClient jdbc, CurrentUser currentUser, DepartmentService departments, DesignationService positions,
			TitleService titles, TeamService teams, ProjectService projects, EmploymentTypeService employmentTypes) {
		this.employmentTypes = employmentTypes;
		this.titles = titles;
		this.teams = teams;
		this.projects = projects;
		this.departments = departments;
		this.positions = positions;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	public Set<String> keys() {
		return DEFS.keySet();
	}

	@Override
	@Transactional(readOnly = true)
	public List<Map<String, Object>> load(String listKey) {
		Def d = def(listKey);
		String cols = d.cols().stream().map(Col::db).collect(Collectors.joining(", "));
		List<Map<String, Object>> loaded = jdbc.sql("select id, " + cols + " from " + d.table() + " where org_id = :org order by " + d.order())
				.param("org", currentUser.orgId())
				.query((rs, i) -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", rs.getObject("id", UUID.class).toString());
					for (Col col : d.cols()) {
						row.put(col.ui(), read(rs.getObject(col.db()), col.type()));
					}
					return row;
				}).list();
		if ("designations".equals(listKey)) {
			presentPositions(loaded);
		}
		if ("projects".equals(listKey)) {
			// the editor shows "On hold"; the database holds ON_HOLD
			for (Map<String, Object> row : loaded) {
				com.ivoyant.hrms.org.domain.ProjectStatus st = com.ivoyant.hrms.org.domain.ProjectStatus.parse((String) row.get("status"));
				row.put("status", st == null ? row.get("status") : st.label());
			}
		}
		return loaded;
	}

	/** The positions editor shows tracks by label and, for INR, bands in lakhs; the database holds enum names and full amounts. */
	private void presentPositions(List<Map<String, Object>> rows) {
		boolean lakhs = "INR".equalsIgnoreCase(jdbc.sql("select currency from org where id = :o").param("o", currentUser.orgId())
				.query(String.class).optional().orElse("INR"));
		for (Map<String, Object> row : rows) {
			com.ivoyant.hrms.org.domain.PositionTrack track = com.ivoyant.hrms.org.domain.PositionTrack.parse((String) row.get("track"));
			row.put("track", track == null ? row.get("track") : track.label());
			if (lakhs) {
				for (String key : List.of("bmin", "bmax")) {
					if (row.get(key) instanceof Number n) {
						row.put(key, BigDecimal.valueOf(n.doubleValue()).movePointLeft(5).stripTrailingZeros().doubleValue());
					}
				}
			}
		}
	}

	@Override
	@Transactional
	public void upsert(String listKey, List<Map<String, Object>> rows) {
		if ("departments".equals(listKey)) {
			departments.bulkUpsert(rows);
			return;
		}
		if ("designations".equals(listKey)) {
			positions.bulkUpsert(rows);
			return;
		}
		if ("titles".equals(listKey)) {
			titles.bulkUpsert(rows);
			return;
		}
		if ("teams".equals(listKey)) {
			teams.bulkUpsert(rows);
			return;
		}
		if ("emptypes".equals(listKey)) {
			employmentTypes.bulkUpsert(rows);
			return;
		}
		if ("projects".equals(listKey)) {
			projects.bulkUpsert(rows);
			return;
		}
		Def d = def(listKey);
		UUID org = currentUser.orgId();
		Set<UUID> existing = ids(d, org);

		// rows first without self references (a parent may be a row created in the same save), then the references
		for (Map<String, Object> row : rows) {
			UUID id = UUID.fromString(row.get("id").toString());
			if (existing.contains(id)) {
				update(d, org, id, row, false);
			}
			else {
				insert(d, org, id, row);
			}
		}
		for (Map<String, Object> row : rows) {
			if (d.cols().stream().anyMatch(Col::self)) {
				update(d, org, UUID.fromString(row.get("id").toString()), row, true);
			}
		}
	}

	@Override
	@Transactional
	public void deleteMissing(String listKey, Set<UUID> keep) {
		if ("departments".equals(listKey)) {
			departments.bulkDelete(keep);
			return;
		}
		if ("designations".equals(listKey)) {
			positions.bulkDelete(keep);
			return;
		}
		if ("titles".equals(listKey)) {
			titles.bulkDelete(keep);
			return;
		}
		if ("teams".equals(listKey)) {
			teams.bulkDelete(keep);
			return;
		}
		if ("emptypes".equals(listKey)) {
			employmentTypes.bulkDelete(keep);
			return;
		}
		if ("projects".equals(listKey)) {
			projects.bulkDelete(keep);
			return;
		}
		Def d = def(listKey);
		UUID org = currentUser.orgId();
		for (UUID id : ids(d, org)) {
			if (keep.contains(id)) {
				continue;
			}
			String name = jdbc.sql("select " + nameColumn(d) + " from " + d.table() + " where id = :id")
					.param("id", id).query(String.class).optional().orElse(id.toString());
			try {
				jdbc.sql("delete from " + d.table() + " where id = :id and org_id = :org").param("id", id)
						.param("org", org).update();
			}
			catch (DataIntegrityViolationException ex) {
				throw new InUseException("'" + name + "' is still in use. Deactivate it instead of deleting it.");
			}
		}
	}

	private Set<UUID> ids(Def d, UUID org) {
		return Set.copyOf(jdbc.sql("select id from " + d.table() + " where org_id = :org").param("org", org)
				.query(UUID.class).list());
	}

	private void insert(Def d, UUID org, UUID id, Map<String, Object> row) {
		List<String> cols = new ArrayList<>(List.of("id", "org_id"));
		List<String> params = new ArrayList<>(List.of(":id", ":org"));
		for (Col col : d.cols()) {
			if (col.self()) {
				continue;
			}
			cols.add(col.db());
			params.add(":" + col.ui());
		}
		var stmt = jdbc.sql("insert into " + d.table() + " (" + String.join(", ", cols) + ") values ("
				+ String.join(", ", params) + ")").param("id", id).param("org", org);
		for (Col col : d.cols()) {
			if (!col.self()) {
				stmt = bind(stmt, col, row.get(col.ui()));
			}
		}
		stmt.update();
	}

	private void update(Def d, UUID org, UUID id, Map<String, Object> row, boolean selfRefsOnly) {
		List<Col> cols = d.cols().stream().filter(col -> col.self() == selfRefsOnly).toList();
		if (cols.isEmpty()) {
			return;
		}
		String sets = cols.stream().map(col -> col.db() + " = :" + col.ui()).collect(Collectors.joining(", "));
		var stmt = jdbc.sql("update " + d.table() + " set " + sets
				+ ", updated_at = now(), updated_by = :actor, version = version + 1 where id = :id and org_id = :org")
				.param("id", id).param("org", org).param("actor", currentUser.employeeId(), Types.OTHER);
		for (Col col : cols) {
			stmt = bind(stmt, col, row.get(col.ui()));
		}
		stmt.update();
	}

	private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec stmt, Col col, Object raw) {
		return switch (col.type()) {
			case TEXT -> stmt.param(col.ui(), blankToNull(raw), Types.VARCHAR);
			case INT -> stmt.param(col.ui(), integer(raw), Types.INTEGER);
			case NUM -> stmt.param(col.ui(), number(raw), Types.NUMERIC);
			case BOOL -> stmt.param(col.ui(),
					raw == null ? "active".equals(col.ui()) : Boolean.parseBoolean(raw.toString()), Types.BOOLEAN);
			case UUID -> stmt.param(col.ui(), uuid(raw), Types.OTHER);
		};
	}

	private static Object read(Object db, T type) {
		if (db == null) {
			return type == T.BOOL ? Boolean.FALSE : null;
		}
		return switch (type) {
			case UUID -> db.toString();
			case NUM -> db instanceof BigDecimal bd ? bd.stripTrailingZeros().doubleValue() : db;
			default -> db;
		};
	}

	private static String nameColumn(Def d) {
		return d.cols().stream().filter(col -> "name".equals(col.ui())).map(Col::db).findFirst().orElse("id::text");
	}

	private static String blankToNull(Object raw) {
		return raw == null || raw.toString().isBlank() ? null : raw.toString().trim();
	}

	private static Integer integer(Object raw) {
		Number n = number(raw);
		return n == null ? null : n.intValue();
	}

	private static BigDecimal number(Object raw) {
		if (raw == null || raw.toString().isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(raw.toString().trim());
		}
		catch (NumberFormatException ex) {
			throw new BadRequestException("'" + raw + "' is not a number");
		}
	}

	private static UUID uuid(Object raw) {
		if (raw == null || raw.toString().isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.toString());
		}
		catch (IllegalArgumentException ex) {
			throw new BadRequestException("'" + raw + "' is not a valid id");
		}
	}

	private static Def def(String listKey) {
		Def d = DEFS.get(listKey);
		if (d == null) {
			throw new BadRequestException("Unknown list '" + listKey + "'");
		}
		return d;
	}
}
