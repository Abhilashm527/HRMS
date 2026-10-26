package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.onboarding.domain.HireStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The pipeline: filtered list, board, tile counts, CSV. */
@Service
public class HireQueryService {

	public record Filter(String q, String status, UUID departmentId, UUID locationId, String window, String sort, int page, int size) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final HireStatusService status;

	HireQueryService(JdbcClient jdbc, CurrentUser currentUser, HireStatusService status) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.status = status;
	}

	private static final String FROM = """
			from onboarding_hire h
			join department d on d.id = h.department_id
			join location l on l.id = h.location_id
			join employment_type et on et.id = h.employment_type_id
			where h.org_id = :org and h.cancelled_at is null""";

	private record Where(String sql, Map<String, Object> params) {
	}

	private Where where(Filter f) {
		StringBuilder sql = new StringBuilder();
		Map<String, Object> p = new LinkedHashMap<>();
		LocalDate today = status.today();
		p.put("org", currentUser.orgId());
		p.put("today", today);
		if (f.q() != null && !f.q().isBlank()) {
			sql.append(" and (lower(h.first_name || ' ' || h.last_name) like :q or lower(h.title) like :q or lower(h.personal_email) like :q or lower(d.name) like :q)");
			p.put("q", "%" + f.q().trim().toLowerCase() + "%");
		}
		if (f.status() != null && !f.status().isBlank() && !f.status().equalsIgnoreCase("ALL")) {
			if (f.status().equalsIgnoreCase("ATTENTION")) {
				sql.append(" and h.status in ('REVIEW_PENDING', 'ACTION_NEEDED')");
			}
			else {
				sql.append(" and h.status = :status");
				p.put("status", f.status().toUpperCase());
			}
		}
		if (f.departmentId() != null) {
			sql.append(" and h.department_id = :dept");
			p.put("dept", f.departmentId());
		}
		if (f.locationId() != null) {
			sql.append(" and h.location_id = :loc");
			p.put("loc", f.locationId());
		}
		String w = f.window() == null ? "ANY" : f.window().toUpperCase();
		switch (w) {
			case "7", "14", "30" -> {
				sql.append(" and h.status <> 'JOINED' and h.doj between :today and :until");
				p.put("until", today.plusDays(Integer.parseInt(w)));
			}
			case "LATER" -> {
				sql.append(" and h.doj > :later");
				p.put("later", today.plusDays(30));
			}
			case "JOINED" -> sql.append(" and (h.status = 'JOINED' or h.doj < :today)");
			default -> sql.append(" and not (h.status = 'JOINED' and h.joined_at < now() - interval '30 days')");
		}
		return new Where(sql.toString(), p);
	}

	private static String order(Filter f) {
		return "-doj".equals(f.sort()) ? " order by h.doj desc, h.created_at desc" : " order by h.doj asc, h.created_at desc";
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(Filter f) {
		Where w = where(f);
		long total = jdbc.sql("select count(*) " + FROM + w.sql()).params(w.params()).query(Long.class).single();
		int size = Math.max(1, Math.min(f.size() <= 0 ? 25 : f.size(), 200));
		int page = Math.max(0, f.page());
		List<Row> rows = load("select " + HireRepo.COLUMNS + ", d.name as dept_name, l.name as loc_name, et.name as et_name " + FROM + w.sql() + order(f)
				+ " limit " + size + " offset " + (long) page * size, w.params());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", build(rows));
		out.put("page", page);
		out.put("size", size);
		out.put("total", total);
		out.put("counts", counts());
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> board(Filter f) {
		Where w = where(new Filter(f.q(), null, f.departmentId(), f.locationId(), f.window(), f.sort(), 0, 0));
		Map<String, Object> out = new LinkedHashMap<>();
		for (String st : HireStatus.ORDER) {
			Map<String, Object> params = new LinkedHashMap<>(w.params());
			params.put("st", st);
			out.put(st, build(load("select " + HireRepo.COLUMNS + ", d.name as dept_name, l.name as loc_name, et.name as et_name " + FROM + w.sql()
					+ " and h.status = :st" + order(f) + " limit 200", params)));
		}
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> counts() {
		LocalDate today = status.today();
		Map<String, Object> out = jdbc.sql("""
				select count(*) filter (where status <> 'JOINED') as in_pipeline,
				       count(*) filter (where status = 'DRAFT') as drafts,
				       count(*) filter (where status in ('REVIEW_PENDING', 'ACTION_NEEDED')) as attention,
				       count(*) filter (where status <> 'JOINED' and doj between :t and :t30) as joining30,
				       count(*) filter (where status <> 'JOINED' and doj between :t and :t7) as joiningweek,
				       count(*) filter (where status not in ('JOINED', 'READY_TO_JOIN') and doj between :t and :t7) as joiningweeknotready
				from onboarding_hire where org_id = :org and cancelled_at is null""").param("org", currentUser.orgId()).param("t", today)
				.param("t7", today.plusDays(7)).param("t30", today.plusDays(30)).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("inPipeline", rs.getLong(1));
					m.put("drafts", rs.getLong(2));
					m.put("attention", rs.getLong(3));
					m.put("joining30", rs.getLong(4));
					m.put("joiningWeek", rs.getLong(5));
					m.put("joiningWeekNotReady", rs.getLong(6));
					return m;
				}).single();
		Map<String, Long> by = new LinkedHashMap<>();
		HireStatus.ORDER.forEach(s -> by.put(s, 0L));
		jdbc.sql("select status, count(*) from onboarding_hire where org_id = :org and cancelled_at is null group by status").param("org", currentUser.orgId())
				.query((rs, i) -> by.put(rs.getString(1), rs.getLong(2))).list();
		out.put("byStatus", by);
		out.put("docsToReview", jdbc.sql("""
				select count(*) from onboarding_hire_document d join onboarding_hire h on h.id = d.hire_id
				where h.org_id = :org and h.cancelled_at is null and h.joined_at is null and d.status = 'SUBMITTED'""").param("org", currentUser.orgId())
				.query(Long.class).single());
		return out;
	}

	@Transactional(readOnly = true)
	public String exportCsv(Filter f, List<UUID> ids) {
		List<Map<String, Object>> rows;
		if (ids != null && !ids.isEmpty()) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("org", currentUser.orgId());
			p.put("ids", ids);
			rows = build(load("select " + HireRepo.COLUMNS + ", d.name as dept_name, l.name as loc_name, et.name as et_name " + FROM + " and h.id in (:ids) order by h.doj", p));
		}
		else {
			Where w = where(f);
			rows = build(load("select " + HireRepo.COLUMNS + ", d.name as dept_name, l.name as loc_name, et.name as et_name " + FROM + w.sql() + order(f), w.params()));
		}
		Map<UUID, String> upns = jdbc.sql("select hire_id, upn from onboarding_m365 where state <> 'DISABLED'").query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
				.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
		StringBuilder sb = new StringBuilder("Name,Title,Type,Department,Location,Joining date,Status,Docs verified,Docs required,Docs in review,Work account,Work email,Invite sent,Reminders\n");
		for (Map<String, Object> r : rows) {
			@SuppressWarnings("unchecked")
			Map<String, Object> docs = (Map<String, Object>) r.get("docs");
			sb.append(String.join(",", csv(r.get("name")), csv(r.get("title")), csv(name(r.get("employmentType"))), csv(name(r.get("department"))), csv(name(r.get("location"))),
					csv(r.get("doj")), csv(r.get("status")), csv(docs.get("verified")), csv(docs.get("required")), csv(docs.get("submitted")), csv(r.get("m365")),
					csv(upns.get((UUID) r.get("id"))), csv(r.get("invitedAt")), csv(r.get("reminders")))).append('\n');
		}
		return sb.toString();
	}

	private static String name(Object o) {
		return o instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : "";
	}

	private static String csv(Object o) {
		String s = o == null ? "" : o.toString();
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
			s = "'" + s; // spreadsheet formula injection
		}
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	// ------------------------------------------------------------------ rows

	private record Row(Hire hire, String dept, String loc, String type) {
	}

	private List<Row> load(String sql, Map<String, Object> params) {
		return jdbc.sql(sql).params(params).query((rs, i) -> new Row(HireRepo.map(rs), rs.getString("dept_name"), rs.getString("loc_name"), rs.getString("et_name"))).list();
	}

	private List<Map<String, Object>> build(Collection<Row> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = rows.stream().map(r -> r.hire().id()).toList();
		Map<UUID, Map<String, Object>> docs = new LinkedHashMap<>();
		jdbc.sql("""
				select hire_id, count(*) filter (where required), count(*) filter (where required and status = 'VERIFIED'), count(*) filter (where status = 'SUBMITTED'),
				       count(*) filter (where status = 'MISSING'), count(*) filter (where status = 'REJECTED'), count(*)
				from onboarding_hire_document where hire_id in (:ids) group by hire_id""").param("ids", ids).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("required", rs.getInt(2));
					m.put("verified", rs.getInt(3));
					m.put("submitted", rs.getInt(4));
					m.put("missing", rs.getInt(5));
					m.put("rejected", rs.getInt(6));
					m.put("total", rs.getInt(7));
					docs.put(rs.getObject(1, UUID.class), m);
					return 0;
				}).list();
		Map<UUID, String> m365 = new LinkedHashMap<>();
		jdbc.sql("select hire_id, state from onboarding_m365 where hire_id in (:ids)").param("ids", ids).query((rs, i) -> m365.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
		Map<UUID, Map<String, Object>> invites = new LinkedHashMap<>();
		jdbc.sql("select hire_id, first_sent_at, reminders from onboarding_invite where hire_id in (:ids) and revoked_at is null").param("ids", ids).query((rs, i) -> {
			invites.put(rs.getObject(1, UUID.class), Map.of("sentAt", rs.getObject(2, OffsetDateTime.class), "reminders", rs.getInt(3)));
			return 0;
		}).list();
		LocalDate today = status.today();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Row r : rows) {
			Hire h = r.hire();
			out.add(build(h, r.type(), r.dept(), r.loc(), today, docs.getOrDefault(h.id(), emptyDocs()), m365.getOrDefault(h.id(), "NONE"), invites.get(h.id())));
		}
		return out;
	}

	private static Map<String, Object> emptyDocs() {
		return Map.of("required", 0, "verified", 0, "submitted", 0, "missing", 0, "rejected", 0, "total", 0);
	}

	/** A single hire as a pipeline row (used by the detail response too). */
	static Map<String, Object> row(JdbcClient jdbc, Hire h, LocalDate today, Map<String, Object> docs, String m365, Map<String, Object> invite) {
		String type = jdbc.sql("select name from employment_type where id = :id").param("id", h.employmentTypeId()).query(String.class).optional().orElse("");
		String dept = jdbc.sql("select name from department where id = :id").param("id", h.departmentId()).query(String.class).optional().orElse("");
		String loc = jdbc.sql("select name from location where id = :id").param("id", h.locationId()).query(String.class).optional().orElse("");
		return build(h, type, dept, loc, today, docs, m365, invite);
	}

	private static Map<String, Object> build(Hire h, String type, String dept, String loc, LocalDate today, Map<String, Object> docs, String m365,
			Map<String, Object> invite) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", h.id());
		m.put("name", h.name());
		m.put("first", h.first());
		m.put("last", h.last());
		m.put("title", h.title());
		m.put("employmentType", Map.of("id", h.employmentTypeId(), "name", type));
		m.put("department", Map.of("id", h.departmentId(), "name", dept));
		m.put("location", Map.of("id", h.locationId(), "name", loc));
		m.put("doj", h.doj());
		m.put("daysToJoin", java.time.temporal.ChronoUnit.DAYS.between(today, h.doj()));
		m.put("status", h.status());
		m.put("stages", h.stages());
		m.put("nextStage", HireStatus.next(h.stages()));
		m.put("docs", docs);
		m.put("m365", m365);
		m.put("invitedAt", invite == null ? null : invite.get("sentAt"));
		m.put("reminders", invite == null ? 0 : invite.get("reminders"));
		return m;
	}
}
