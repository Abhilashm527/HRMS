package com.ivoyant.hrms.requests.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.requests.RequestTypeUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request types by category (letters, reimbursements, help-desk). A type can be hidden (past requests keep it) but not removed once used;
 * renaming is free because requests point at the type, not its name.
 */
@Service
public class RequestTypeService {

	private static final String ENTITY = "RequestType";

	public record RequestType(UUID id, String category, String name, boolean active, int sort, long usage,
			UUID helpdeskTeamRoleId, String helpdeskTeam, Long version) {
	}

	public record Listing(Map<String, Long> counts, List<RequestType> items) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final RequestTypeUsage usage;
	private final Audit audit;

	public RequestTypeService(JdbcClient jdbc, CurrentUser currentUser, RequestTypeUsage usage, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.usage = usage;
		this.audit = audit;
	}

	private RequestType map(java.sql.ResultSet rs) throws java.sql.SQLException {
		UUID id = rs.getObject("id", UUID.class);
		return new RequestType(id, rs.getString("category"), rs.getString("name"), rs.getBoolean("active"), rs.getInt("sort"),
				usage.requestsOfType(id), rs.getObject("helpdesk_team_role_id", UUID.class), rs.getString("team"), rs.getLong("version"));
	}

	private static final String SELECT = "select t.*, r.name as team from request_type t left join role r on r.id = t.helpdesk_team_role_id where t.org_id = :org";

	/** {@code counts} is how many are shown per category (the tabs' badges). */
	@Transactional(readOnly = true)
	public Listing list(String category) {
		String cat = category == null || category.isBlank() ? null : category(category);
		List<RequestType> items = jdbc.sql(SELECT + (cat == null ? "" : " and t.category = :cat") + " order by t.category, t.sort, t.name")
				.param("org", currentUser.orgId()).param("cat", cat, java.sql.Types.VARCHAR).query((rs, i) -> map(rs)).list();
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbc.sql("select category, count(*) from request_type where org_id = :org and active group by category").param("org", currentUser.orgId())
				.query((rs, i) -> {
					counts.put(rs.getString(1), rs.getLong(2));
					return 0;
				}).list();
		return new Listing(counts, items);
	}

	/** The shown types of a category: what an employee picks from. */
	@Transactional(readOnly = true)
	public List<RequestType> options(String category) {
		return list(category).items().stream().filter(RequestType::active).toList();
	}

	@Transactional(readOnly = true)
	public RequestType get(UUID id) {
		return jdbc.sql(SELECT + " and t.id = :id").param("org", currentUser.orgId()).param("id", id).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Request type " + id + " not found"));
	}

	/** Body: {@code category, name, helpdeskTeamRoleId}. */
	@Transactional
	public RequestType create(Map<String, Object> body) {
		String cat = category(body.get("category") == null ? "" : body.get("category").toString());
		String name = name(body.get("name"));
		requireFreeName(cat, name, null);
		UUID role = roleId(body.get("helpdeskTeamRoleId"), cat);
		int sort = jdbc.sql("select coalesce(max(sort), 0) + 10 from request_type where org_id = :org and category = :cat")
				.param("org", currentUser.orgId()).param("cat", cat).query(Integer.class).single();
		UUID id = jdbc.sql("insert into request_type (org_id, category, name, sort, helpdesk_team_role_id, created_by, updated_by) values (:org, :cat, :n, :s, :role, :by, :by) returning id")
				.param("org", currentUser.orgId()).param("cat", cat).param("n", name).param("s", sort).param("role", role, java.sql.Types.OTHER)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		audit.publish("requests", ENTITY, id, "ADDED", null, Map.of("detail", "Added " + name));
		return get(id);
	}

	/** Merge patch: {@code name, active, helpdeskTeamRoleId}. */
	@Transactional
	public RequestType update(UUID id, Map<String, Object> patch, String ifMatch) {
		RequestType cur = get(id);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(cur.version()))) {
			throw new StaleException("Someone else changed this request type. Reload and try again.");
		}
		String name = patch.containsKey("name") ? name(patch.get("name")) : cur.name();
		boolean active = patch.containsKey("active") ? Boolean.parseBoolean(String.valueOf(patch.get("active"))) : cur.active();
		UUID role = patch.containsKey("helpdeskTeamRoleId") ? roleId(patch.get("helpdeskTeamRoleId"), cur.category()) : cur.helpdeskTeamRoleId();
		if (!name.equalsIgnoreCase(cur.name())) {
			requireFreeName(cur.category(), name, id);
		}
		if (cur.active() && !active && jdbc.sql("select count(*) from request_type where org_id = :org and category = :cat and active and id <> :id")
				.param("org", currentUser.orgId()).param("cat", cur.category()).param("id", id).query(Long.class).single() == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("active", "LAST_ACTIVE", "Keep at least one type shown")));
		}
		jdbc.sql("update request_type set name = :n, active = :a, helpdesk_team_role_id = :role, updated_at = now(), updated_by = :by, version = version + 1 where id = :id")
				.param("n", name).param("a", active).param("role", role, java.sql.Types.OTHER).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
				.param("id", id).update();
		if (!name.equals(cur.name())) {
			audit.publish("requests", ENTITY, id, "RENAMED", null, Map.of("detail", cur.name() + " → " + name));
		}
		if (active != cur.active()) {
			audit.publish("requests", ENTITY, id, active ? "SHOWN" : "HIDDEN", null, Map.of("detail", name));
		}
		return get(id);
	}

	@Transactional
	public void delete(UUID id) {
		RequestType cur = get(id);
		long used = usage.requestsOfType(id);
		if (used > 0) {
			throw new InUseException(cur.name() + " is used by " + used + (used == 1 ? " request" : " requests") + ". Hide it instead.", Map.of("requests", used));
		}
		jdbc.sql("delete from request_type where id = :id").param("id", id).update();
		audit.publish("requests", ENTITY, id, "REMOVED", null, Map.of("detail", "Removed " + cur.name()));
	}

	@Transactional
	public Listing reorder(String category, List<UUID> ids) {
		int sort = 10;
		for (UUID id : ids) {
			jdbc.sql("update request_type set sort = :s, updated_at = now() where id = :id and org_id = :org").param("s", sort).param("id", id)
					.param("org", currentUser.orgId()).update();
			sort += 10;
		}
		return list(category);
	}

	// ---- rules ----

	private void requireFreeName(String category, String name, UUID self) {
		boolean taken = jdbc.sql("select count(*) from request_type where org_id = :org and category = :cat and lower(name) = lower(:n) and (cast(:self as uuid) is null or id <> cast(:self as uuid))")
				.param("org", currentUser.orgId()).param("cat", category).param("n", name).param("self", self, java.sql.Types.OTHER)
				.query(Long.class).single() > 0;
		if (taken) {
			throw new CodedConflictException("NAME_TAKEN", name + " already exists");
		}
	}

	private UUID roleId(Object v, String category) {
		if (v == null || v.toString().isBlank()) {
			return null;
		}
		if (!"HELPDESK".equals(category)) {
			throw new UnprocessableException(List.of(ErrorItem.field("helpdeskTeamRoleId", "INVALID", "Only help-desk types have a team")));
		}
		try {
			UUID id = UUID.fromString(v.toString());
			if (jdbc.sql("select count(*) from role where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId()).query(Long.class).single() == 0) {
				throw new UnprocessableException(List.of(ErrorItem.field("helpdeskTeamRoleId", "INVALID", "That role does not exist")));
			}
			return id;
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("helpdeskTeamRoleId", "INVALID", "That is not a valid id")));
		}
	}

	private static String name(Object v) {
		String t = v == null ? "" : v.toString().trim().replaceAll("\\s+", " ");
		if (t.length() < 2 || t.length() > 60) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "NAME_LENGTH", "Name must be 2–60 characters")));
		}
		return t;
	}

	static String category(String text) {
		String t = text.trim().toUpperCase(Locale.ROOT);
		return switch (t) {
			case "LETTER", "LETTERS" -> "LETTER";
			case "REIMBURSEMENT", "REIMBURSEMENTS" -> "REIMBURSEMENT";
			case "HELPDESK", "HELP-DESK", "HELP DESK" -> "HELPDESK";
			default -> throw new UnprocessableException(List.of(ErrorItem.field("category", "INVALID", "Category must be Letters, Reimbursements or Helpdesk")));
		};
	}
}
