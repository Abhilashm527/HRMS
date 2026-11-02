package com.ivoyant.hrms.search;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.documents.DocumentSearch;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One search box over the app. Every source applies the same rule its own screens do: HR sees everyone's leave and requests, others
 * only their own; published policies and holidays are for everyone; assets need asset access (otherwise just what you hold);
 * documents follow folder access. Each hit carries the link that opens it.
 */
@Service
class SearchService {

	static final List<String> GROUPS = List.of("People", "Leave requests", "Requests", "Policies", "Documents", "Assets", "Holidays");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final IamDirectory iam;
	private final DocumentSearch documents;

	SearchService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, IamDirectory iam, DocumentSearch documents) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.iam = iam;
		this.documents = documents;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> search(String q, String group, int limit) {
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("q", q);
		if (needle.length() < 2) {
			out.put("groups", List.of());
			return out;
		}
		UUID me = currentUser.requireEmployeeId();
		int n = Math.max(1, Math.min(limit, 60));
		String like = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		List<Map<String, Object>> groups = new ArrayList<>();
		for (String g : GROUPS) {
			if (group != null && !group.isBlank() && !group.equalsIgnoreCase(g)) {
				continue;
			}
			List<Map<String, Object>> hits = switch (g) {
				case "People" -> people(like);
				case "Leave requests" -> leave(like, needle, me);
				case "Requests" -> requests(like, needle, me);
				case "Policies" -> policies(like, me);
				case "Documents" -> docs(needle);
				case "Assets" -> assets(like, me);
				default -> holidays(like);
			};
			if (!hits.isEmpty()) {
				groups.add(Map.of("group", g, "total", hits.size(), "items", hits.size() > n ? hits.subList(0, n) : hits));
			}
		}
		out.put("groups", groups);
		return out;
	}

	private static Map<String, Object> hit(Object id, String title, String sub, String meta, String link) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("title", title);
		m.put("sub", sub == null ? "" : sub);
		m.put("meta", meta);
		m.put("link", link);
		return m;
	}

	private List<Map<String, Object>> people(String like) {
		boolean hr = access.has("employee", "VIEW");
		return jdbc.sql("""
				select e.id, e.display_name, e.employee_code, jt.name, d.name, l.name, e.status, e.work_email
				from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id left join location l on l.id = e.location_id
				where e.org_id = :org and e.archived_at is null and (:hr or e.status <> 'EXITED')
				  and (lower(e.display_name) like :q or lower(e.employee_code) like :q or lower(coalesce(e.work_email, '')) like :q or lower(coalesce(jt.name, '')) like :q)
				order by (lower(e.display_name) like :start) desc, lower(e.display_name) limit 61""").param("org", org()).param("hr", hr).param("q", like)
				.param("start", like.substring(1)).query((rs, i) -> {
					String sub = String.join(" · ", nonBlank(rs.getString(4), rs.getString(5), rs.getString(3)));
					String st = rs.getString(7);
					return hit(rs.getObject(1, UUID.class), rs.getString(2), sub, "ACTIVE".equals(st) ? rs.getString(6) : "EXITED".equals(st) ? "Exited" : "On notice",
							"/employees/" + rs.getObject(1, UUID.class));
				}).list();
	}

	private List<Map<String, Object>> leave(String like, String needle, UUID me) {
		boolean hr = access.has("leave", "VIEW");
		return jdbc.sql("""
				select r.id, r.no, e.display_name, t.name, r.from_date, r.to_date, r.days, r.status, r.reason, r.employee_id
				from leave_request r join employee e on e.id = r.employee_id join leave_type t on t.id = r.type_id
				where r.org_id = :org and (:hr or r.employee_id = :me)
				  and (lower(e.display_name) like :q or lower(t.name) like :q or lower(r.reason) like :q or 'lr-' || lpad(r.no::text, 4, '0') like :q)
				order by r.applied_at desc limit 61""").param("org", org()).param("hr", hr).param("me", me).param("q", like).query((rs, i) -> {
			LocalDate a = rs.getObject(5, LocalDate.class);
			LocalDate b = rs.getObject(6, LocalDate.class);
			String when = a.equals(b) ? DAY.format(a) : DAY.format(a) + " – " + DAY.format(b);
			boolean mine = me.equals(rs.getObject(10, UUID.class));
			return hit(rs.getObject(1, UUID.class), rs.getString(3) + " · " + rs.getString(4),
					"LR-" + String.format("%04d", rs.getLong(2)) + " · " + when + " · " + rs.getString(9), title(rs.getString(8)),
					hr ? "/leave?request=" + rs.getObject(1, UUID.class) : mine ? "/me/leave" : "/leave");
		}).list();
	}

	private List<Map<String, Object>> requests(String like, String needle, UUID me) {
		boolean hr = access.has("requests", "VIEW");
		return jdbc.sql("""
				select r.id, r.no, e.display_name, t.name, r.kind, r.title, r.note, r.status, r.employee_id
				from employee_request r join employee e on e.id = r.employee_id join request_type t on t.id = r.type_id
				where r.org_id = :org and (:hr or r.employee_id = :me)
				  and (lower(e.display_name) like :q or lower(t.name) like :q or lower(coalesce(r.title, '')) like :q or lower(r.note) like :q or 'rq-' || r.no::text like :q)
				order by r.no desc limit 61""").param("org", org()).param("hr", hr).param("me", me).param("q", like).query((rs, i) -> {
			String kind = rs.getString(5);
			String tab = "LETTER".equals(kind) ? "letters" : "REIMBURSEMENT".equals(kind) ? "reimb" : "tickets";
			String name = rs.getString(6) == null || rs.getString(6).isBlank() ? rs.getString(4) : rs.getString(6);
			boolean mine = me.equals(rs.getObject(9, UUID.class));
			return hit(rs.getObject(1, UUID.class), name + " · " + rs.getString(3), "RQ-" + rs.getLong(2) + " · " + rs.getString(7), title(rs.getString(8)),
					hr && !mine ? "/inbox?tab=" + tab + "&open=" + rs.getObject(1, UUID.class) : "/me/requests?tab=" + tab);
		}).list();
	}

	private List<Map<String, Object>> policies(String like, UUID me) {
		boolean hr = roleKeys(me).stream().anyMatch(Set.of("super_admin", "hr_admin", "hr_ops")::contains);
		return jdbc.sql("""
				select p.id, p.name, c.name, v.version, p.archived_at is not null,
				       (lower(p.name) like :q or lower(c.name) like :q) as by_name
				from policy p join policy_category c on c.id = p.category_id
				left join lateral (select x.version, x.sections from policy_version x where x.policy_id = p.id order by x.published_at desc limit 1) v on true
				left join policy_draft d on d.policy_id = p.id
				where p.org_id = :org and (:hr or (p.archived_at is null and v.version is not null))
				  and (lower(p.name) like :q or lower(c.name) like :q or lower(coalesce(v.sections::text, '')) like :q
				       or (:hr and lower(coalesce(d.sections::text, '')) like :q))
				order by by_name desc, lower(p.name) limit 61""").param("org", org()).param("hr", hr).param("q", like).query((rs, i) -> {
			String v = rs.getString(4);
			boolean archived = rs.getBoolean(5);
			String sub = rs.getString(3) + (v == null ? " · draft" : " · v" + v) + (archived ? " · archived" : "") + (rs.getBoolean(6) ? "" : " · mentioned in the text");
			return hit(rs.getObject(1, UUID.class), rs.getString(2), sub, null, (hr ? "/policies?open=" : "/me/policies?open=") + rs.getObject(1, UUID.class));
		}).list();
	}

	private List<Map<String, Object>> docs(String needle) {
		return documents.search(needle, 61).stream().map(d -> hit(d.get("id"), (String) d.get("name"),
				("FOLDER".equals(d.get("kind")) ? "Folder · " : "") + d.get("where"), null,
				"FOLDER".equals(d.get("kind")) ? "/documents?folder=" + d.get("folderId") : "/documents?folder=" + d.get("folderId") + "&file=" + d.get("id"))).toList();
	}

	private List<Map<String, Object>> assets(String like, UUID me) {
		boolean all = access.has("assets", "VIEW");
		List<Map<String, Object>> out = new ArrayList<>(jdbc.sql("""
				select a.id, a.model, a.tag, a.serial, t.name, s.name, h.display_name
				from asset a join asset_type t on t.id = a.type_id join asset_status s on s.id = a.status_id
				left join asset_assignment x on x.asset_id = a.id and x.returned_on is null left join employee h on h.id = x.employee_id
				where a.org_id = :org and a.deleted_at is null and (:all or x.employee_id = :me)
				  and (lower(a.model) like :q or lower(a.tag) like :q or lower(a.serial) like :q or lower(coalesce(h.display_name, '')) like :q)
				order by a.tag limit 61""").param("org", org()).param("all", all).param("me", me).param("q", like)
				.query((rs, i) -> hit(rs.getObject(1, UUID.class), rs.getString(2) + " · " + rs.getString(3),
						rs.getString(5) + " · S/N " + rs.getString(4) + (rs.getString(7) == null ? " · in stock" : " · " + rs.getString(7)), rs.getString(6),
						all ? "/assets?mode=hardware&open=" + rs.getObject(1, UUID.class) : "/me/assets"))
				.list());
		if (all) {
			out.addAll(jdbc.sql("""
					select l.id, l.name, l.lid, l.vendor from license l where l.org_id = :org and l.deleted_at is null
					  and (lower(l.name) like :q or lower(l.lid) like :q or lower(coalesce(l.vendor, '')) like :q) order by lower(l.name) limit 30""")
					.param("org", org()).param("q", like)
					.query((rs, i) -> hit(rs.getObject(1, UUID.class), rs.getString(2), "Software licence · " + rs.getString(3) + (rs.getString(4) == null ? "" : " · " + rs.getString(4)),
							null, "/assets?mode=software&open=" + rs.getObject(1, UUID.class)))
					.list());
		}
		return out;
	}

	private List<Map<String, Object>> holidays(String like) {
		int y = LocalDate.now().getYear();
		return jdbc.sql("""
				select distinct on (h.date, h.name) h.id, h.name, h.date, t.name from holiday h join holiday_calendar c on c.id = h.calendar_id
				join holiday_type t on t.id = h.type_id
				where c.org_id = :org and extract(year from h.date) between :y and :y + 1 and lower(h.name) like :q order by h.date, h.name limit 30""")
				.param("org", org()).param("y", y).param("q", like)
				.query((rs, i) -> hit(rs.getObject(1, UUID.class), rs.getString(2), DAY.format(rs.getObject(3, LocalDate.class)) + " · " + rs.getString(4), null, "/calendar"))
				.list();
	}

	private Set<String> roleKeys(UUID me) {
		Set<UUID> ids = iam.roleIdsOf(me);
		Set<String> keys = new HashSet<>();
		for (RoleRef r : iam.roles()) {
			if (ids.contains(r.id())) {
				keys.add(r.key());
			}
		}
		return keys;
	}

	private static List<String> nonBlank(String... s) {
		List<String> out = new ArrayList<>();
		for (String x : s) {
			if (x != null && !x.isBlank()) {
				out.add(x);
			}
		}
		return out;
	}

	private static String title(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String t = s.replace('_', ' ').toLowerCase(Locale.ROOT);
		return Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	private UUID org() {
		return currentUser.orgId();
	}
}
