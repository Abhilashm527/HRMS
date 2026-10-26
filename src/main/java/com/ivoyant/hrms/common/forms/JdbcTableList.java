package com.ivoyant.hrms.common.forms;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A {@link TableBackedList} driven by a column map. Each column says how the list editor's key maps to a table column; the
 * {@code sql} kind carries its own read and write expressions for anything that is not a plain value (a name that stands for an id,
 * a comma list stored as JSON, a label stored as an enum code). Rows keep their ids, and the row order becomes the {@code sort} column.
 */
public final class JdbcTableList implements TableBackedList {

	public enum Kind { TEXT, INT, BOOL, UUID, SQL }

	/**
	 * {@code ui} is the editor's key (also the SQL parameter name), {@code db} the column. For SQL columns {@code read} is an expression
	 * over alias {@code t}, {@code write} an expression over the {@code :ui} parameter (and {@code :org}). {@code insertOnly} columns are
	 * set when the row is created and never changed afterwards.
	 */
	public record Col(String ui, String db, Kind kind, String read, String write, boolean insertOnly) {

		public static Col text(String ui, String db) {
			return new Col(ui, db, Kind.TEXT, null, null, false);
		}

		public static Col integer(String ui, String db) {
			return new Col(ui, db, Kind.INT, null, null, false);
		}

		public static Col bool(String ui, String db) {
			return new Col(ui, db, Kind.BOOL, null, null, false);
		}

		public static Col uuid(String ui, String db) {
			return new Col(ui, db, Kind.UUID, null, null, false);
		}

		public static Col sql(String ui, String db, String read, String write) {
			return new Col(ui, db, Kind.SQL, read, write, false);
		}

		/** A column the editor does not show: written when the row is created, from an expression over the other parameters. */
		public static Col hidden(String db, String write) {
			return new Col("_" + db, db, Kind.SQL, null, write, true);
		}

		public Col once() {
			return new Col(ui, db, kind, read, write, true);
		}
	}

	/** Checks a list as a whole; see {@link TableBackedList#validate}. */
	@FunctionalInterface
	public interface Rules {

		void check(List<Map<String, Object>> rows, Map<String, List<Map<String, Object>>> all, List<ErrorItem> errors);
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final String module;
	private final String key;
	private final int order;
	private final String table;
	private final String filter;
	private final Map<String, String> constants;
	private final String orderBy;
	private final List<Col> cols;
	private final String nameColumn;
	private final Rules rules;

	/**
	 * @param filter extra {@code where} (over alias {@code t}) that scopes the table to this list, such as {@code t.kind = 'HARDWARE'}
	 * @param constants columns set on insert to a SQL literal, such as {@code kind -> 'HARDWARE'}
	 */
	public JdbcTableList(JdbcClient jdbc, CurrentUser currentUser, String module, String key, int order, String table, String filter,
			Map<String, String> constants, String orderBy, String nameColumn, List<Col> cols, Rules rules) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.module = module;
		this.key = key;
		this.order = order;
		this.table = table;
		this.filter = filter;
		this.constants = constants;
		this.orderBy = orderBy;
		this.nameColumn = nameColumn;
		this.cols = cols;
		this.rules = rules;
	}

	@Override
	public String module() {
		return module;
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public int order() {
		return order;
	}

	@Override
	public List<Map<String, Object>> load() {
		List<Col> shown = cols.stream().filter(c -> !c.ui().startsWith("_")).toList();
		StringBuilder sql = new StringBuilder("select t.id");
		for (int i = 0; i < shown.size(); i++) {
			Col c = shown.get(i);
			sql.append(", ").append(c.kind() == Kind.SQL ? "(" + c.read() + ")" : "t." + c.db()).append(" as c").append(i);
		}
		sql.append(" from ").append(table).append(" t where t.org_id = :org");
		if (filter != null) {
			sql.append(" and ").append(filter);
		}
		sql.append(" order by ").append(orderBy);
		return jdbc.sql(sql.toString()).param("org", currentUser.orgId()).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class).toString());
			for (int i = 0; i < shown.size(); i++) {
				Col c = shown.get(i);
				Object v = rs.getObject("c" + i);
				row.put(c.ui(), v instanceof UUID u ? u.toString() : v == null && c.kind() == Kind.BOOL ? Boolean.FALSE : v);
			}
			return row;
		}).list();
	}

	@Override
	public void upsert(List<Map<String, Object>> rows) {
		UUID org = currentUser.orgId();
		Set<UUID> existing = ids();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			if (existing.contains(id)) {
				update(org, id, row, (i + 1) * 10);
			}
			else {
				insert(org, id, row, (i + 1) * 10);
			}
		}
	}

	private void insert(UUID org, UUID id, Map<String, Object> row, int sort) {
		boolean ownSort = cols.stream().anyMatch(c -> "sort".equals(c.db()));
		List<String> names = new ArrayList<>(List.of("id", "org_id", "created_by", "updated_by"));
		List<String> values = new ArrayList<>(List.of(":id", ":org", ":actor", ":actor"));
		if (!ownSort) {
			names.add("sort");
			values.add(":sort");
		}
		constants.forEach((c, lit) -> {
			names.add(c);
			values.add(lit);
		});
		for (Col c : cols) {
			names.add(c.db());
			values.add(c.kind() == Kind.SQL ? "(" + c.write() + ")" : ":" + c.ui());
		}
		var stmt = jdbc.sql("insert into " + table + " (" + String.join(", ", names) + ") values (" + String.join(", ", values) + ")")
				.param("id", id).param("org", org).param("actor", currentUser.employeeId(), Types.OTHER);
		if (!ownSort) {
			stmt = stmt.param("sort", sort);
		}
		bind(stmt, row, cols).update();
	}

	private void update(UUID org, UUID id, Map<String, Object> row, int sort) {
		List<Col> changing = cols.stream().filter(c -> !c.insertOnly()).toList();
		String sets = changing.stream().map(c -> c.db() + " = " + (c.kind() == Kind.SQL ? "(" + c.write() + ")" : ":" + c.ui()))
				.collect(Collectors.joining(", "));
		boolean ownSort = cols.stream().anyMatch(c -> "sort".equals(c.db()));
		var stmt = jdbc.sql("update " + table + " set " + sets + (ownSort ? "" : ", sort = :sort")
				+ ", updated_at = now(), updated_by = :actor, version = version + 1 where id = :id and org_id = :org")
				.param("id", id).param("org", org).param("actor", currentUser.employeeId(), Types.OTHER);
		if (!ownSort) {
			stmt = stmt.param("sort", sort);
		}
		bind(stmt, row, changing).update();
	}

	private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec stmt, Map<String, Object> row, List<Col> columns) {
		for (Col c : columns) {
			Object raw = row.get(c.ui());
			String text = raw == null || raw.toString().isBlank() ? null : raw.toString().trim();
			stmt = switch (c.kind()) {
				case TEXT, SQL -> stmt.param(c.ui(), text, Types.VARCHAR);
				case INT -> stmt.param(c.ui(), text == null ? null : (int) Double.parseDouble(text), Types.INTEGER);
				case BOOL -> stmt.param(c.ui(), raw == null ? "active".equals(c.ui()) : Boolean.parseBoolean(raw.toString()), Types.BOOLEAN);
				case UUID -> stmt.param(c.ui(), text == null ? null : java.util.UUID.fromString(text), Types.OTHER);
			};
		}
		return stmt;
	}

	@Override
	public void deleteMissing(Set<UUID> keep) {
		UUID org = currentUser.orgId();
		for (UUID id : ids()) {
			if (keep.contains(id)) {
				continue;
			}
			String name = jdbc.sql("select cast(" + nameColumn + " as text) from " + table + " t where t.id = :id").param("id", id).query(String.class)
					.optional().orElse(id.toString());
			try {
				jdbc.sql("delete from " + table + " where id = :id and org_id = :org").param("id", id).param("org", org).update();
			}
			catch (DataIntegrityViolationException ex) {
				throw new InUseException("'" + name + "' is still in use. Switch it off instead of deleting it.", Map.of("name", name));
			}
		}
	}

	private Set<UUID> ids() {
		return Set.copyOf(jdbc.sql("select t.id from " + table + " t where t.org_id = :org" + (filter == null ? "" : " and " + filter))
				.param("org", currentUser.orgId()).query(UUID.class).list());
	}

	@Override
	public void validate(List<Map<String, Object>> rows, Map<String, List<Map<String, Object>>> all, List<ErrorItem> errors) {
		if (rules != null) {
			rules.check(rows, all, errors);
		}
	}
}
