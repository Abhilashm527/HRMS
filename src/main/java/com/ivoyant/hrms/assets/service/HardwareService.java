package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetIds;
import com.ivoyant.hrms.assets.service.AssetSupport.Status;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.AssetAssignedEvent;
import com.ivoyant.hrms.common.event.AssetReturnedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The hardware register: one row per physical thing, one holder at a time, a life from purchase to retirement. */
@Service
public class HardwareService {

	static final String KIND = "HARDWARE";
	private static final String SPECS = "asset_hardware";
	private static final Set<String> RETIRE_REASONS = Set.of("END_OF_LIFE", "LOST", "DAMAGED_BEYOND_REPAIR", "SOLD", "RETURNED_TO_CLIENT", "LEASE_ENDED");
	private static final Set<String> CONDITIONS = Set.of("GOOD", "DAMAGED", "MISSING");

	private static final String FROM = " " + """
			 from asset a join asset_type t on t.id = a.type_id left join asset_category c on c.id = t.category_id
			 join asset_status s on s.id = a.status_id join asset_ownership o on o.id = a.ownership_id
			 left join asset_assignment ag on ag.asset_id = a.id and ag.returned_on is null
			 left join employee h on h.id = ag.employee_id left join job_title ht on ht.id = h.title_id
			 left join location l on l.id = a.location_id
			 where a.org_id = :org and a.deleted_at is null""";
	private static final String COLUMNS = """
			select a.id, a.tag, a.model, a.serial, a.type_id, t.name as type_name, t.max_age_years, c.id as cat_id, c.name as cat_name, a.status_id, s.name as status_name,
			       s.color as status_color, s.assignable, s.system_key, a.ownership_id, o.name as own_name, a.ownership_data::text as own_data, a.location_id, l.name as loc_name,
			       a.vendor, a.invoice_no, a.purchase_cost, a.purchased_on, a.warranty_from, a.warranty_until, a.insurer, a.policy_no, a.insured_until, a.return_due,
			       a.lost, a.retired_on, a.retire_reason, a.notes, a.created_by, a.created_at, a.updated_by, a.updated_at, a.version,
			       ag.employee_id as holder_id, ag.assigned_on as since, h.employee_code as h_code, h.display_name as h_name, h.first_name as h_first, h.last_name as h_last,
			       ht.name as h_title, h.photo_version as h_photo""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final AssetSupport support;
	private final AssetIds ids;
	private final SpecStore specs;
	private final FileStore store;
	private final Notifier notifier;
	private final ApplicationEventPublisher events;
	private final Audit audit;
	private final RequestLinks links;
	private final ObjectMapper json;

	HardwareService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, AssetSupport support, AssetIds ids, SpecStore specs, FileStore store,
			Notifier notifier, ApplicationEventPublisher events, Audit audit, RequestLinks links, ObjectMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.support = support;
		this.ids = ids;
		this.specs = specs;
		this.store = store;
		this.notifier = notifier;
		this.events = events;
		this.audit = audit;
		this.links = links;
		this.json = json;
	}

	private boolean canSeeMoney() {
		return access.has("assets", "EDIT");
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	// ================================================================== rows

	private Map<String, Object> listRow(ResultSet rs) throws SQLException {
		String sys = rs.getString("system_key");
		boolean assignable = rs.getBoolean("assignable");
		boolean held = rs.getObject("holder_id") != null;
		boolean retired = "RETIRED".equals(sys);
		UUID typeId = rs.getObject("type_id", UUID.class);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", rs.getObject("id", UUID.class));
		m.put("tag", rs.getString("tag"));
		m.put("model", rs.getString("model"));
		m.put("serial", rs.getString("serial"));
		m.put("type", Map.of("id", typeId, "name", rs.getString("type_name"), "color", AssetSupport.typeColor(typeId)));
		Status st = new Status(rs.getObject("status_id", UUID.class), rs.getString("status_name"), rs.getInt("status_color"), assignable, sys, true, KIND);
		m.put("status", st.view());
		m.put("holder", held ? AssetSupport.card(rs.getObject("holder_id", UUID.class), rs.getString("h_code"), rs.getString("h_name"), rs.getString("h_first"),
				rs.getString("h_last"), rs.getString("h_title"), null, rs.getInt("h_photo")) : null);
		m.put("since", rs.getObject("since", LocalDate.class));
		m.put("warranty", support.dateState(rs.getObject("warranty_from", LocalDate.class), rs.getObject("warranty_until", LocalDate.class)));
		Map<String, Object> actions = new LinkedHashMap<>();
		actions.put("assign", assignable && !held && !retired);
		actions.put("recover", held);
		actions.put("repaired", "IN_REPAIR".equals(sys));
		m.put("actions", actions);
		return m;
	}

	// ================================================================== lists

	@Transactional(readOnly = true)
	public Map<String, Object> list(String q, UUID typeId, UUID statusId, UUID categoryId, UUID locationId, UUID employeeId, String warranty, int page, int size, String sort) {
		StringBuilder where = new StringBuilder();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("org", currentUser.orgId());
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(a.tag) like :q or lower(a.model) like :q or lower(a.serial) like :q or lower(coalesce(h.display_name, '')) like :q or lower(coalesce(h.employee_code, '')) like :q)");
			p.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
		}
		if (typeId != null) {
			where.append(" and a.type_id = :type");
			p.put("type", typeId);
		}
		if (statusId != null) {
			where.append(" and a.status_id = :status");
			p.put("status", statusId);
		}
		if (categoryId != null) {
			where.append(" and t.category_id = :cat");
			p.put("cat", categoryId);
		}
		if (locationId != null) {
			where.append(" and a.location_id = :loc");
			p.put("loc", locationId);
		}
		if (employeeId != null) {
			where.append(" and ag.employee_id = :emp");
			p.put("emp", employeeId);
		}
		if ("EXPIRED".equalsIgnoreCase(warranty)) {
			where.append(" and a.warranty_until < :today");
			p.put("today", support.today());
		}
		else if ("ENDING".equalsIgnoreCase(warranty)) {
			where.append(" and a.warranty_until between :today and :soon");
			p.put("today", support.today());
			p.put("soon", support.today().plusDays(30));
		}
		String order = switch (sort == null ? "" : sort) {
			case "model" -> " order by lower(a.model), a.tag";
			case "since" -> " order by ag.assigned_on desc nulls last, a.tag";
			case "warranty" -> " order by a.warranty_until nulls last, a.tag";
			case "status" -> " order by s.sort, a.tag";
			default -> " order by a.tag";
		};
		int pageSize = Math.max(1, Math.min(size <= 0 ? 25 : size, 100));
		int pg = Math.max(1, page);
		long total = jdbc.sql("select count(*)" + FROM + where).params(p).query(Long.class).single();
		List<Map<String, Object>> items = jdbc.sql(COLUMNS + FROM + where + order + " limit " + pageSize + " offset " + (long) (pg - 1) * pageSize).params(p)
				.query((rs, i) -> listRow(rs)).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("page", pg);
		out.put("size", pageSize);
		out.put("total", total);
		Map<String, Object> facets = new LinkedHashMap<>();
		facets.put("types", jdbc.sql("select t.id, t.name, count(*) from asset a join asset_type t on t.id = a.type_id where a.org_id = :org and a.deleted_at is null group by t.id, t.name, t.sort order by t.sort, t.name")
				.param("org", currentUser.orgId()).query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2), "n", rs.getLong(3))).list());
		facets.put("statuses", jdbc.sql("select s.id, s.name, count(*) from asset a join asset_status s on s.id = a.status_id where a.org_id = :org and a.deleted_at is null group by s.id, s.name, s.sort order by s.sort")
				.param("org", currentUser.orgId()).query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2), "n", rs.getLong(3))).list());
		out.put("facets", facets);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> available() {
		LocalDate today = support.today();
		List<Map<String, Object>> rows = jdbc.sql(COLUMNS + FROM + " and s.assignable and ag.id is null and coalesce(s.system_key, '') <> 'RETIRED' order by t.name, a.tag")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					Map<String, Object> m = listRow(rs);
					m.put("typeId", rs.getObject("type_id", UUID.class));
					return m;
				}).list();
		Map<UUID, Long> openRequests = new LinkedHashMap<>();
		jdbc.sql("select asset_type_id, count(*) from asset_request where org_id = :org and kind = 'HARDWARE' and status in ('PENDING', 'APPROVED') group by asset_type_id")
				.param("org", currentUser.orgId()).query((rs, i) -> openRequests.put(rs.getObject(1, UUID.class), rs.getLong(2))).list();
		Map<UUID, Map<String, Object>> groups = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			@SuppressWarnings("unchecked")
			Map<String, Object> type = (Map<String, Object>) r.get("type");
			UUID tid = (UUID) type.get("id");
			Map<String, Object> g = groups.computeIfAbsent(tid, k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("type", type);
				m.put("available", 0);
				m.put("endingWarranty", 0);
				m.put("openRequests", openRequests.getOrDefault(tid, 0L));
				m.put("items", new ArrayList<Map<String, Object>>());
				return m;
			});
			g.put("available", (Integer) g.get("available") + 1);
			@SuppressWarnings("unchecked")
			Map<String, Object> w = (Map<String, Object>) r.get("warranty");
			if ("ENDING".equals(w.get("state"))) {
				g.put("endingWarranty", (Integer) g.get("endingWarranty") + 1);
			}
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
			items.add(r);
		}
		List<Map<String, Object>> inRepair = jdbc.sql(COLUMNS + FROM + " and s.system_key = 'IN_REPAIR' order by a.tag").param("org", currentUser.orgId())
				.query((rs, i) -> listRow(rs)).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("summary", groups.values().stream().map(g -> Map.of("type", g.get("type"), "available", g.get("available"), "endingWarranty", g.get("endingWarranty"),
				"openRequests", g.get("openRequests"))).toList());
		out.put("groups", new ArrayList<>(groups.values()));
		out.put("inRepair", inRepair);
		out.put("asOf", today);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> dashboard() {
		UUID org = currentUser.orgId();
		LocalDate today = support.today();
		Map<String, Object> totals = jdbc.sql("""
				select count(*) filter (where coalesce(s.system_key, '') <> 'RETIRED') as total,
				       count(*) filter (where s.system_key = 'ASSIGNED') as assigned,
				       count(*) filter (where s.assignable and ag.id is null and coalesce(s.system_key, '') <> 'RETIRED') as stock,
				       count(*) filter (where s.assignable and ag.id is null and a.warranty_until between :t and :soon) as stock_ending,
				       coalesce(sum(a.purchase_cost) filter (where coalesce(s.system_key, '') <> 'RETIRED'), 0) as value,
				       count(distinct t.category_id) filter (where coalesce(s.system_key, '') <> 'RETIRED') as cats,
				       count(distinct a.location_id) filter (where coalesce(s.system_key, '') <> 'RETIRED') as locs
				from asset a join asset_type t on t.id = a.type_id join asset_status s on s.id = a.status_id
				left join asset_assignment ag on ag.asset_id = a.id and ag.returned_on is null
				where a.org_id = :org and a.deleted_at is null""").param("org", org).param("t", today).param("soon", today.plusDays(30)).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("total", rs.getLong(1));
					m.put("assigned", rs.getLong(2));
					m.put("stock", rs.getLong(3));
					m.put("stockEnding", rs.getLong(4));
					m.put("value", rs.getLong(5));
					m.put("cats", rs.getLong(6));
					m.put("locs", rs.getLong(7));
					return m;
				}).single();
		long total = (Long) totals.get("total");
		long assigned = (Long) totals.get("assigned");
		List<Map<String, Object>> kpis = new ArrayList<>();
		kpis.add(kpi("total", "Total hardware", total, totals.get("cats") + " categories · " + totals.get("locs") + " locations"));
		kpis.add(kpi("assigned", "Assigned", assigned, (total == 0 ? 0 : Math.round(100.0 * assigned / total)) + "% allocation"));
		kpis.add(kpi("stock", "In stock", totals.get("stock"), totals.get("stockEnding") + " ending warranty"));
		if (canSeeMoney()) {
			kpis.add(kpi("value", "Asset value", totals.get("value"), "book value at cost"));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("kpis", kpis);
		out.put("byStatus", jdbc.sql("select s.name, s.color, count(*) from asset a join asset_status s on s.id = a.status_id where a.org_id = :org and a.deleted_at is null group by s.id, s.name, s.color, s.sort order by s.sort")
				.param("org", org).query((rs, i) -> Map.<String, Object>of("label", rs.getString(1), "color", rs.getInt(2), "n", rs.getLong(3))).list());
		out.put("byType", jdbc.sql("select t.name, count(*) from asset a join asset_type t on t.id = a.type_id join asset_status s on s.id = a.status_id where a.org_id = :org and a.deleted_at is null and coalesce(s.system_key, '') <> 'RETIRED' group by t.id, t.name order by count(*) desc, t.name")
				.param("org", org).query((rs, i) -> Map.<String, Object>of("label", rs.getString(1), "n", rs.getLong(2))).list());
		out.put("deptAllocation", jdbc.sql("""
				select d.name, count(*) from asset_assignment ag join asset a on a.id = ag.asset_id join employee e on e.id = ag.employee_id left join department d on d.id = e.department_id
				where a.org_id = :org and a.deleted_at is null and ag.returned_on is null group by d.id, d.name order by count(*) desc""").param("org", org)
				.query((rs, i) -> Map.<String, Object>of("department", rs.getString(1) == null ? "No department" : rs.getString(1), "n", rs.getLong(2))).list());
		out.put("stock", jdbc.sql(COLUMNS + FROM + " and ((s.assignable and ag.id is null and coalesce(s.system_key, '') <> 'RETIRED') or s.system_key = 'IN_REPAIR') order by s.assignable desc, a.tag limit 8")
				.param("org", org).query((rs, i) -> {
					Map<String, Object> m = listRow(rs);
					@SuppressWarnings("unchecked")
					Map<String, Object> type = (Map<String, Object>) m.get("type");
					Map<String, Object> o = new LinkedHashMap<>();
					o.put("id", m.get("id"));
					o.put("tag", m.get("tag"));
					o.put("model", m.get("model"));
					o.put("type", type.get("name"));
					o.put("status", ((Map<?, ?>) m.get("status")).get("name"));
					o.put("actions", m.get("actions"));
					return o;
				}).list());
		out.put("alerts", alerts("ASSET_ALERT"));
		out.put("activity", activity("asset_event", "asset_id"));
		return out;
	}

	static Map<String, Object> kpi(String key, String label, Object value, String sub) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", key);
		m.put("label", label);
		m.put("value", value);
		m.put("sub", sub);
		return m;
	}

	List<Map<String, Object>> alerts(String type) {
		UUID me = currentUser.employeeId();
		if (me == null) {
			return List.of();
		}
		return jdbc.sql("select id, title, body, created_at from notification_inbox where employee_id = :e and type = :t and read_at is null order by created_at desc limit 10")
				.param("e", me).param("t", type).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getLong(1));
					m.put("title", rs.getString(2));
					m.put("sub", rs.getString(3));
					m.put("severity", "warning");
					m.put("at", rs.getObject(4, OffsetDateTime.class));
					return m;
				}).list();
	}

	List<Map<String, Object>> activity(String table, String fk) {
		return jdbc.sql("select e.title, e.description, e.at, a.display_name, a.first_name, a.last_name from " + table + " e left join employee a on a.id = e.actor_id where e.org_id = :org order by e.at desc limit 10")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("who", rs.getString(4) == null ? "System" : rs.getString(4));
					m.put("initials", rs.getString(4) == null ? "SY" : (rs.getString(5).substring(0, 1) + rs.getString(6).substring(0, 1)).toUpperCase());
					m.put("text", rs.getString(1));
					m.put("at", rs.getObject(3, OffsetDateTime.class));
					return m;
				}).list();
	}

	// ================================================================== detail

	private Map<String, Object> row(UUID id, boolean lock) {
		return jdbc.sql(COLUMNS + FROM + " and a.id = :id" + (lock ? " for update of a" : "")).param("org", currentUser.orgId()).param("id", id).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			ResultSet r = rs;
			for (String c : new String[] {"id", "tag", "model", "serial", "type_id", "type_name", "max_age_years", "cat_id", "cat_name", "status_id", "status_name", "status_color",
					"assignable", "system_key", "ownership_id", "own_name", "own_data", "location_id", "loc_name", "vendor", "invoice_no", "purchase_cost", "purchased_on",
					"warranty_from", "warranty_until", "insurer", "policy_no", "insured_until", "return_due", "lost", "retired_on", "retire_reason", "notes", "created_by",
					"created_at", "updated_by", "updated_at", "version", "holder_id", "since", "h_code", "h_name", "h_first", "h_last", "h_title", "h_photo"}) {
				m.put(c, r.getObject(c));
			}
			return m;
		}).optional().orElseThrow(() -> new NotFoundException("Asset " + id + " not found"));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID id) {
		Map<String, Object> r = row(id, false);
		boolean money = canSeeMoney();
		boolean retired = "RETIRED".equals(r.get("system_key"));
		boolean held = r.get("holder_id") != null;
		boolean assignable = Boolean.TRUE.equals(r.get("assignable"));
		LocalDate purchased = date(r.get("purchased_on"));
		Integer maxAge = r.get("max_age_years") == null ? null : ((Number) r.get("max_age_years")).intValue();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", r.get("id"));
		out.put("tag", r.get("tag"));
		out.put("model", r.get("model"));
		out.put("serial", r.get("serial"));
		UUID typeId = (UUID) r.get("type_id");
		out.put("type", Map.of("id", typeId, "name", r.get("type_name"), "color", AssetSupport.typeColor(typeId)));
		out.put("category", r.get("cat_id") == null ? null : Map.of("id", r.get("cat_id"), "name", r.get("cat_name")));
		Status st = new Status((UUID) r.get("status_id"), (String) r.get("status_name"), ((Number) r.get("status_color")).intValue(), assignable, (String) r.get("system_key"), true, KIND);
		out.put("status", st.view());
		Map<String, Object> own = new LinkedHashMap<>();
		own.put("id", r.get("ownership_id"));
		own.put("name", r.get("own_name"));
		own.put("fields", ownershipFields(r));
		out.put("ownership", own);
		out.put("location", r.get("location_id") == null ? null : Map.of("id", r.get("location_id"), "name", r.get("loc_name")));
		List<Map<String, Object>> basic = new ArrayList<>();
		basic.add(kv("Type", r.get("type_name")));
		basic.add(kv("Category", r.get("cat_name")));
		basic.add(kv("Purchase date", purchased));
		if (money) {
			basic.add(kv("Purchase cost", r.get("purchase_cost")));
			basic.add(kv("Vendor", r.get("vendor")));
			basic.add(kv("Invoice", r.get("invoice_no")));
		}
		basic.add(kv("Location", r.get("loc_name")));
		if (purchased != null) {
			basic.add(kv("Age", ChronoUnit.MONTHS.between(purchased, support.today()) + " months"));
		}
		out.put("basic", basic);
		out.put("specs", specs.read(SPECS, id, ((String) r.get("type_name")).toLowerCase(Locale.ROOT)));
		out.put("files", files(id));
		out.put("holder", held ? holder(r) : null);
		out.put("warranty", support.dateState(date(r.get("warranty_from")), date(r.get("warranty_until"))));
		Map<String, Object> ins = new LinkedHashMap<>(support.dateState(null, date(r.get("insured_until"))));
		ins.put("insurer", r.get("insurer"));
		ins.put("policyNo", r.get("policy_no"));
		out.put("insurance", ins);
		out.put("returnDue", r.get("return_due"));
		out.put("refreshDue", purchased != null && maxAge != null ? purchased.plusYears(maxAge) : null);
		out.put("lost", r.get("lost"));
		out.put("retiredOn", r.get("retired_on"));
		out.put("retireReason", r.get("retire_reason"));
		out.put("notes", r.get("notes"));
		Map<String, Object> admin = new LinkedHashMap<>();
		admin.put("createdAt", r.get("created_at"));
		admin.put("createdBy", support.employeeName((UUID) r.get("created_by")));
		admin.put("updatedAt", r.get("updated_at"));
		admin.put("updatedBy", support.employeeName((UUID) r.get("updated_by")));
		out.put("admin", admin);
		if (money) {
			out.put("purchaseCost", r.get("purchase_cost"));
			out.put("vendor", r.get("vendor"));
			out.put("invoiceNo", r.get("invoice_no"));
		}
		out.put("version", r.get("version"));
		boolean canEdit = access.has("assets", "EDIT");
		Map<String, Object> can = new LinkedHashMap<>();
		can.put("edit", canEdit);
		can.put("assign", canEdit && assignable && !held && !retired);
		can.put("recover", canEdit && held);
		can.put("repair", canEdit && !retired && !"IN_REPAIR".equals(r.get("system_key")));
		can.put("repaired", canEdit && "IN_REPAIR".equals(r.get("system_key")));
		can.put("retire", canEdit && !retired && !held);
		out.put("can", can);
		return out;
	}

	private Map<String, Object> holder(Map<String, Object> r) {
		Map<String, Object> m = AssetSupport.card((UUID) r.get("holder_id"), (String) r.get("h_code"), (String) r.get("h_name"), (String) r.get("h_first"), (String) r.get("h_last"),
				(String) r.get("h_title"), null, ((Number) r.get("h_photo")).intValue());
		m.put("since", r.get("since"));
		return m;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> ownershipFields(Map<String, Object> r) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			Map<String, Object> data = json.readValue((String) r.get("own_data"), Map.class);
			data.forEach((k, v) -> out.add(kv(k, v)));
		}
		catch (RuntimeException ex) {
			// unreadable captured data: show none
		}
		return out;
	}

	private static Map<String, Object> kv(String k, Object v) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("k", k);
		m.put("v", v);
		return m;
	}

	private static LocalDate date(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof java.sql.Date d) {
			return d.toLocalDate();
		}
		return (LocalDate) o;
	}

	// ================================================================== timeline

	@Transactional(readOnly = true)
	public Object timeline(UUID id, String q, String kind, String order, int page, boolean csv) {
		row(id, false);
		List<String> kinds = switch (kind == null ? "ALL" : kind.toUpperCase(Locale.ROOT)) {
			case "ASSIGNMENT" -> List.of("ASSIGNED", "RECOVERED");
			case "STATUS" -> List.of("STATUS", "RETIRED", "CREATED");
			case "REPAIR" -> List.of("REPAIR_OUT", "REPAIR_IN");
			case "EDIT" -> List.of("EDITED", "NOTE", "IMPORTED");
			case "FILE" -> List.of("FILE");
			default -> List.of();
		};
		boolean asc = "asc".equalsIgnoreCase(order);
		String sql = """
				select e.kind, e.title, e.description, e.prev_value, e.new_value, e.note, e.at, a.display_name, p.display_name
				from asset_event e left join employee a on a.id = e.actor_id left join employee p on p.id = e.employee_id
				where e.asset_id = :id and (:all or e.kind in (:kinds)) and (cast(:q as text) is null or lower(e.title || ' ' || coalesce(e.description, '') || ' ' || coalesce(e.note, '')) like :q)
				order by e.at""" + (asc ? " asc" : " desc") + ", e.id" + (asc ? " asc" : " desc") + (csv ? "" : " limit 50 offset " + Math.max(0, page) * 50);
		List<Map<String, Object>> items = jdbc.sql(sql).param("id", id).param("all", kinds.isEmpty()).param("kinds", kinds.isEmpty() ? List.of("-") : kinds)
				.param("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%").query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("kind", rs.getString(1));
					m.put("title", rs.getString(2));
					m.put("description", rs.getString(3));
					m.put("previous", rs.getString(4));
					m.put("value", rs.getString(5));
					m.put("note", rs.getString(6));
					m.put("at", rs.getObject(7, OffsetDateTime.class));
					m.put("by", rs.getString(8));
					m.put("person", rs.getString(9));
					return m;
				}).list();
		if (!csv) {
			return Map.of("items", items, "count", items.size(), "page", Math.max(0, page));
		}
		StringBuilder sb = new StringBuilder("When,Kind,Title,Description,Previous,New,Note,By\n");
		for (Map<String, Object> m : items) {
			sb.append(String.join(",", csv(m.get("at")), csv(m.get("kind")), csv(m.get("title")), csv(m.get("description")), csv(m.get("previous")), csv(m.get("value")),
					csv(m.get("note")), csv(m.get("by")))).append('\n');
		}
		return sb.toString();
	}

	static String csv(Object o) {
		String s = o == null ? "" : o.toString();
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
			s = "'" + s;
		}
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	// ================================================================== create / edit

	@Transactional
	public Map<String, Object> create(Map<String, Object> b, boolean imported) {
		UUID org = currentUser.orgId();
		UUID typeId = uuid(b.get("typeId"), "typeId");
		Map<String, Object> type = jdbc.sql("select t.id, t.name, t.category_id, t.active from asset_type t where t.id = :id and t.org_id = :org and t.kind = 'HARDWARE'")
				.param("id", typeId).param("org", org).query((rs, i) -> Map.<String, Object>of("name", rs.getString(2), "category", String.valueOf(rs.getObject(3)), "active", rs.getBoolean(4)))
				.optional().orElseThrow(() -> problem("typeId", "INVALID", "Choose a hardware type"));
		if (!(Boolean) type.get("active")) {
			throw problem("typeId", "INVALID", type.get("name") + " is not an active type");
		}
		if (b.get("categoryId") != null && !b.get("categoryId").toString().equals(type.get("category"))) {
			throw problem("categoryId", "INVALID", "That type does not belong to the chosen category");
		}
		String model = text(b.get("model"));
		String serial = text(b.get("serial"));
		if (model == null || serial == null) {
			throw new UnprocessableException(List.of(model == null ? ErrorItem.field("model", "REQUIRED", "Make & model is required") : ErrorItem.field("serial", "REQUIRED", "Serial number is required")));
		}
		requireSerialFree(org, typeId, serial, null);
		UUID ownershipId = uuid(b.get("ownershipId"), "ownershipId");
		Map<String, Object> own = ownership(ownershipId);
		Map<String, Object> ownershipData = b.get("ownershipData") instanceof Map<?, ?> m ? castMap(m) : Map.of();
		LocalDate purchased = date(b.get("purchasedOn"), "purchasedOn");
		LocalDate warrantyFrom = date(b.get("warrantyFrom"), "warrantyFrom");
		LocalDate warrantyUntil = date(b.get("warrantyUntil"), "warrantyUntil");
		LocalDate insuredUntil = date(b.get("insuredUntil"), "insuredUntil");
		LocalDate returnDue = date(b.get("returnDue"), "returnDue");
		if (returnDue == null) {
			returnDue = ownershipDate(ownershipData);
		}
		if ((Boolean) own.get("returnRequired") && returnDue == null) {
			throw problem("returnDue", "OWNERSHIP_FIELD_REQUIRED", "A return or lease-end date is required for " + own.get("name") + " assets");
		}
		if (warrantyFrom == null && warrantyUntil != null) {
			warrantyFrom = purchased;
		}
		Long cost = money(b.get("purchaseCost"));
		Status status = b.get("statusId") == null ? support.statusByKey(KIND, "AVAILABLE") : support.status(uuid(b.get("statusId"), "statusId"));
		if (!KIND.equals(status.kind())) {
			throw problem("statusId", "INVALID", "Choose a hardware status");
		}
		if ("RETIRED".equals(status.systemKey()) || "IN_REPAIR".equals(status.systemKey())) {
			throw problem("statusId", "USE_ACTION", "Create it as available, then " + ("RETIRED".equals(status.systemKey()) ? "retire" : "send it for repair"));
		}
		UUID employeeId = b.get("employeeId") == null || b.get("employeeId").toString().isBlank() ? null : uuid(b.get("employeeId"), "employeeId");
		boolean assign = "ASSIGNED".equals(status.systemKey());
		if (assign && employeeId == null) {
			throw problem("employeeId", "REQUIRED", "Choose who it is assigned to");
		}
		if (assign) {
			requireActiveEmployee(employeeId);
		}
		UUID formatId = ids.formatFor(KIND, ownershipId).orElseThrow(() -> new CodedConflictException("NO_ID_FORMAT", "No asset ID format is set up for " + own.get("name") + " assets"));
		String tag;
		String importedTag = text(b.get("tag"));
		if (importedTag != null) {
			if (jdbc.sql("select count(*) from asset where org_id = :org and tag = :t").param("org", org).param("t", importedTag).query(Long.class).single() > 0) {
				throw new CodedConflictException("ASSET_TAG_TAKEN", "Tag " + importedTag + " already exists");
			}
			tag = importedTag;
		}
		else {
			tag = ids.next(formatId, AssetSupport.code((String) type.get("name")));
		}
		UUID id = UUID.randomUUID();
		UUID location = b.get("locationId") == null || b.get("locationId").toString().isBlank() ? null : uuid(b.get("locationId"), "locationId");
		Status effective = assign ? support.statusByKey(KIND, "ASSIGNED") : status;
		jdbc.sql("""
				insert into asset (id, org_id, tag, type_id, model, serial, status_id, ownership_id, ownership_data, location_id, vendor, invoice_no, purchase_cost, purchased_on,
				  warranty_from, warranty_until, insurer, policy_no, insured_until, return_due, notes, created_by)
				values (:id, :org, :tag, :type, :model, :serial, :status, :own, :od::jsonb, :loc, :vendor, :inv, :cost, :pd, :wf, :wu, :insurer, :policy, :iu, :rd, :notes, :by)""")
				.param("id", id).param("org", org).param("tag", tag).param("type", typeId).param("model", model).param("serial", serial).param("status", effective.id())
				.param("own", ownershipId).param("od", json.writeValueAsString(ownershipData)).param("loc", location, java.sql.Types.OTHER).param("vendor", text(b.get("vendor")))
				.param("inv", text(b.get("invoiceNo"))).param("cost", cost, java.sql.Types.BIGINT).param("pd", purchased).param("wf", warrantyFrom).param("wu", warrantyUntil)
				.param("insurer", text(b.get("insurer"))).param("policy", text(b.get("policyNo"))).param("iu", insuredUntil).param("rd", returnDue).param("notes", text(b.get("notes")))
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		specs.write(SPECS, id, ((String) type.get("name")).toLowerCase(Locale.ROOT), b.get("specs") instanceof Map<?, ?> sm ? castMap(sm) : Map.of(), false);
		support.hardwareEvent(id, imported ? "IMPORTED" : "CREATED", imported ? "Imported" : "Asset created", tag + " · " + model, null, null,
				b.get("aiExtractionId") == null ? null : "Filled from document", null);
		audit.created("assets", "Asset", id, Map.of("tag", tag, "model", model));
		if (assign) {
			doAssign(id, employeeId, support.today(), null, null, false);
		}
		return detail(id);
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> update(UUID id, Map<String, Object> b, String ifMatch) {
		Map<String, Object> r = row(id, true);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(r.get("version")))) {
			throw new StaleException("This asset was changed by someone else. Reload and try again.");
		}
		boolean retired = "RETIRED".equals(r.get("system_key"));
		if (retired) {
			for (String k : b.keySet()) {
				if (!k.equals("notes") && !k.equals("version")) {
					throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RETIRED_READ_ONLY", "A retired asset can only get notes and files");
				}
			}
		}
		UUID org = currentUser.orgId();
		List<String> diffs = new ArrayList<>();
		UUID typeId = b.containsKey("typeId") ? uuid(b.get("typeId"), "typeId") : (UUID) r.get("type_id");
		String typeName = (String) r.get("type_name");
		if (!typeId.equals(r.get("type_id"))) {
			typeName = jdbc.sql("select name from asset_type where id = :id and org_id = :org and kind = 'HARDWARE' and active").param("id", typeId).param("org", org).query(String.class)
					.optional().orElseThrow(() -> problem("typeId", "INVALID", "Choose an active hardware type"));
			diffs.add("Type: " + r.get("type_name") + " → " + typeName);
		}
		String model = b.containsKey("model") ? text(b.get("model")) : (String) r.get("model");
		String serial = b.containsKey("serial") ? text(b.get("serial")) : (String) r.get("serial");
		if (model == null || serial == null) {
			throw problem(model == null ? "model" : "serial", "REQUIRED", "This is required");
		}
		if (!serial.equalsIgnoreCase(String.valueOf(r.get("serial"))) || !typeId.equals(r.get("type_id"))) {
			requireSerialFree(org, typeId, serial, id);
		}
		if (!model.equals(r.get("model"))) {
			diffs.add("Model: " + r.get("model") + " → " + model);
		}
		if (!serial.equals(r.get("serial"))) {
			diffs.add("Serial: " + r.get("serial") + " → " + serial);
		}
		UUID ownershipId = b.containsKey("ownershipId") ? uuid(b.get("ownershipId"), "ownershipId") : (UUID) r.get("ownership_id");
		Map<String, Object> own = ownership(ownershipId);
		Map<String, Object> ownershipData = b.get("ownershipData") instanceof Map<?, ?> m ? castMap(m) : readMap((String) r.get("own_data"));
		LocalDate returnDue = b.containsKey("returnDue") ? date(b.get("returnDue"), "returnDue") : date(r.get("return_due"));
		if (returnDue == null) {
			returnDue = ownershipDate(ownershipData);
		}
		if ((Boolean) own.get("returnRequired") && returnDue == null) {
			throw problem("returnDue", "OWNERSHIP_FIELD_REQUIRED", "A return or lease-end date is required for " + own.get("name") + " assets");
		}
		if (!ownershipId.equals(r.get("ownership_id"))) {
			diffs.add("Ownership: " + r.get("own_name") + " → " + own.get("name"));
		}
		UUID statusId = (UUID) r.get("status_id");
		if (b.containsKey("statusId")) {
			Status wanted = support.status(uuid(b.get("statusId"), "statusId"));
			Status current = support.status(statusId);
			if (!wanted.id().equals(current.id())) {
				boolean free = (wanted.systemKey() == null || "NOT_IN_USE".equals(wanted.systemKey())) && (current.systemKey() == null || "NOT_IN_USE".equals(current.systemKey()));
				if (!free) {
					throw problem("statusId", "USE_ACTION", "Use Assign, Recover, Send for repair or Retire to change to or from " + (current.systemKey() == null || "NOT_IN_USE".equals(current.systemKey()) ? wanted.name() : current.name()));
				}
				statusId = wanted.id();
				diffs.add("Status: " + current.name() + " → " + wanted.name());
			}
		}
		UUID location = b.containsKey("locationId") ? (b.get("locationId") == null || b.get("locationId").toString().isBlank() ? null : uuid(b.get("locationId"), "locationId"))
				: (UUID) r.get("location_id");
		Long cost = b.containsKey("purchaseCost") ? money(b.get("purchaseCost")) : (r.get("purchase_cost") == null ? null : ((Number) r.get("purchase_cost")).longValue());
		LocalDate purchased = b.containsKey("purchasedOn") ? date(b.get("purchasedOn"), "purchasedOn") : date(r.get("purchased_on"));
		LocalDate wFrom = b.containsKey("warrantyFrom") ? date(b.get("warrantyFrom"), "warrantyFrom") : date(r.get("warranty_from"));
		LocalDate wUntil = b.containsKey("warrantyUntil") ? date(b.get("warrantyUntil"), "warrantyUntil") : date(r.get("warranty_until"));
		if (!java.util.Objects.equals(wUntil, date(r.get("warranty_until")))) {
			diffs.add("Warranty until: " + r.get("warranty_until") + " → " + wUntil);
		}
		if (!java.util.Objects.equals(cost, r.get("purchase_cost") == null ? null : ((Number) r.get("purchase_cost")).longValue())) {
			diffs.add("Purchase cost changed");
		}
		int n = jdbc.sql("""
				update asset set type_id = :type, model = :model, serial = :serial, status_id = :status, ownership_id = :own, ownership_data = :od::jsonb, location_id = :loc,
				  vendor = :vendor, invoice_no = :inv, purchase_cost = :cost, purchased_on = :pd, warranty_from = :wf, warranty_until = :wu, insurer = :insurer,
				  policy_no = :policy, insured_until = :iu, return_due = :rd, notes = :notes, version = version + 1, updated_at = now(), updated_by = :by
				where id = :id""").param("type", typeId).param("model", model).param("serial", serial).param("status", statusId).param("own", ownershipId)
				.param("od", json.writeValueAsString(ownershipData)).param("loc", location, java.sql.Types.OTHER)
				.param("vendor", b.containsKey("vendor") ? text(b.get("vendor")) : (String) r.get("vendor")).param("inv", b.containsKey("invoiceNo") ? text(b.get("invoiceNo")) : (String) r.get("invoice_no"))
				.param("cost", cost, java.sql.Types.BIGINT).param("pd", purchased).param("wf", wFrom).param("wu", wUntil)
				.param("insurer", b.containsKey("insurer") ? text(b.get("insurer")) : (String) r.get("insurer")).param("policy", b.containsKey("policyNo") ? text(b.get("policyNo")) : (String) r.get("policy_no"))
				.param("iu", b.containsKey("insuredUntil") ? date(b.get("insuredUntil"), "insuredUntil") : date(r.get("insured_until"))).param("rd", returnDue)
				.param("notes", b.containsKey("notes") ? text(b.get("notes")) : (String) r.get("notes")).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		if (n == 0) {
			throw new NotFoundException("Asset " + id + " not found");
		}
		if (b.get("specs") instanceof Map<?, ?> sm) {
			specs.write(SPECS, id, typeName.toLowerCase(Locale.ROOT), castMap(sm), true);
			diffs.add("Specifications updated");
		}
		else if (!typeId.equals(r.get("type_id"))) {
			specs.write(SPECS, id, typeName.toLowerCase(Locale.ROOT), Map.of(), true);
		}
		if (b.containsKey("notes") && !java.util.Objects.equals(text(b.get("notes")), r.get("notes"))) {
			diffs.add("Notes updated");
		}
		if (!diffs.isEmpty()) {
			support.hardwareEvent(id, "EDITED", "Details edited", String.join("; ", diffs), null, null, null, null);
			audit.updated("assets", "Asset", id, Map.of("version", r.get("version")), Map.of("changes", diffs));
		}
		return detail(id);
	}

	@Transactional
	public void delete(UUID id) {
		Map<String, Object> r = row(id, true);
		long history = jdbc.sql("select count(*) from asset_assignment where asset_id = :id").param("id", id).query(Long.class).single();
		if (history > 0) {
			throw new CodedConflictException("HAS_OPEN_ASSIGNMENT", r.get("tag") + " has been assigned before. Retire it instead of deleting it.");
		}
		jdbc.sql("update asset set deleted_at = now(), updated_at = now(), updated_by = :by where id = :id").param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		audit.deleted("assets", "Asset", id, Map.of("tag", r.get("tag")));
	}

	// ================================================================== actions

	@Transactional
	public Map<String, Object> assign(UUID id, Map<String, Object> b) {
		UUID employeeId = uuid(b.get("employeeId"), "employeeId");
		LocalDate on = b.get("assignedOn") == null ? support.today() : date(b.get("assignedOn"), "assignedOn");
		UUID requestId = b.get("requestId") == null ? null : uuid(b.get("requestId"), "requestId");
		return doAssign(id, employeeId, on, requestId, text(b.get("note")), true);
	}

	private Map<String, Object> doAssign(UUID id, UUID employeeId, LocalDate on, UUID requestId, String note, boolean fromAction) {
		Map<String, Object> r = row(id, true);
		String sys = (String) r.get("system_key");
		if ("RETIRED".equals(sys)) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RETIRED_READ_ONLY", "A retired asset cannot be assigned");
		}
		if (r.get("holder_id") != null && fromAction) {
			throw new CodedConflictException("ALREADY_ASSIGNED", r.get("tag") + " is already assigned to " + r.get("h_name"));
		}
		if (fromAction && !Boolean.TRUE.equals(r.get("assignable"))) {
			throw new CodedConflictException("NOT_ASSIGNABLE", r.get("status_name") + " assets cannot be assigned");
		}
		requireActiveEmployee(employeeId);
		try {
			jdbc.sql("insert into asset_assignment (asset_id, employee_id, assigned_on, request_id, assigned_by, note) values (:a, :e, :d, :rq, :by, :n)")
					.param("a", id).param("e", employeeId).param("d", on).param("rq", requestId, java.sql.Types.OTHER).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
					.param("n", note).update();
		}
		catch (org.springframework.dao.DuplicateKeyException ex) {
			throw new CodedConflictException("ALREADY_ASSIGNED", r.get("tag") + " was just assigned to someone else");
		}
		Status assigned = support.statusByKey(KIND, "ASSIGNED");
		jdbc.sql("update asset set status_id = :s, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("s", assigned.id())
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		String who = support.employeeName(employeeId);
		support.hardwareEvent(id, "ASSIGNED", "Assigned to " + who, null, (String) r.get("status_name"), assigned.name(), note, employeeId);
		links.linkHardware(employeeId, (UUID) r.get("type_id"), id, requestId);
		notifier.inApp(List.of(employeeId), "ASSET_ASSIGNED", r.get("tag") + " is assigned to you", r.get("model") + " · " + r.get("type_name"), "/me/assets");
		events.publishEvent(new AssetAssignedEvent(id, employeeId));
		audit.publish("assets", "Asset", id, "ASSIGN", null, Map.of("employee", employeeId.toString()));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> recover(UUID id, Map<String, Object> b) {
		Map<String, Object> r = row(id, true);
		if (r.get("holder_id") == null) {
			throw new CodedConflictException("NOT_ASSIGNED", r.get("tag") + " is not assigned to anyone");
		}
		String condition = b.get("condition") == null ? "GOOD" : b.get("condition").toString().toUpperCase(Locale.ROOT);
		if (!CONDITIONS.contains(condition)) {
			throw problem("condition", "INVALID", "Condition is Good, Damaged or Missing");
		}
		LocalDate on = b.get("returnedOn") == null ? support.today() : date(b.get("returnedOn"), "returnedOn");
		UUID holder = (UUID) r.get("holder_id");
		jdbc.sql("update asset_assignment set returned_on = greatest(assigned_on, :d), return_condition = :c, returned_by = :by where asset_id = :a and returned_on is null")
				.param("d", on).param("c", condition).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("a", id).update();
		Status next = support.statusByKey(KIND, switch (condition) {
			case "DAMAGED" -> "IN_REPAIR";
			case "MISSING" -> "NOT_IN_USE";
			default -> "AVAILABLE";
		});
		jdbc.sql("update asset set status_id = :s, lost = :lost, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("s", next.id())
				.param("lost", "MISSING".equals(condition)).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		support.hardwareEvent(id, "RECOVERED", "Recovered from " + r.get("h_name"), "Condition: " + condition.toLowerCase(Locale.ROOT), (String) r.get("status_name"), next.name(), text(b.get("note")), holder);
		events.publishEvent(new AssetReturnedEvent(id, holder, condition));
		audit.publish("assets", "Asset", id, "RECOVER", null, Map.of("condition", condition));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> repair(UUID id, Map<String, Object> b) {
		Map<String, Object> r = row(id, true);
		if ("RETIRED".equals(r.get("system_key"))) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RETIRED_READ_ONLY", "A retired asset cannot be repaired");
		}
		if ("IN_REPAIR".equals(r.get("system_key"))) {
			throw new CodedConflictException("NOT_ASSIGNABLE", r.get("tag") + " is already in repair");
		}
		boolean recover = Boolean.TRUE.equals(b.get("recover"));
		if (recover && r.get("holder_id") != null) {
			jdbc.sql("update asset_assignment set returned_on = greatest(assigned_on, :d), return_condition = 'DAMAGED', returned_by = :by where asset_id = :a and returned_on is null")
					.param("d", support.today()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("a", id).update();
			events.publishEvent(new AssetReturnedEvent(id, (UUID) r.get("holder_id"), "DAMAGED"));
		}
		Status repair = support.statusByKey(KIND, "IN_REPAIR");
		jdbc.sql("update asset set status_id = :s, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("s", repair.id())
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		String detail = (b.get("vendor") == null ? "" : "At " + b.get("vendor") + ". ") + (b.get("expectedBack") == null ? "" : "Expected back " + b.get("expectedBack") + ".");
		support.hardwareEvent(id, "REPAIR_OUT", "Sent for repair", detail.isBlank() ? null : detail.trim(), (String) r.get("status_name"), repair.name(), text(b.get("note")), null);
		audit.publish("assets", "Asset", id, "REPAIR", null, null);
		return detail(id);
	}

	@Transactional
	public Map<String, Object> repaired(UUID id, Map<String, Object> b) {
		Map<String, Object> r = row(id, true);
		if (!"IN_REPAIR".equals(r.get("system_key"))) {
			throw new CodedConflictException("NOT_IN_REPAIR", r.get("tag") + " is not in repair");
		}
		Status next = support.statusByKey(KIND, r.get("holder_id") != null ? "ASSIGNED" : "AVAILABLE");
		jdbc.sql("update asset set status_id = :s, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("s", next.id())
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		Long cost = b.get("cost") == null ? null : money(b.get("cost"));
		support.hardwareEvent(id, "REPAIR_IN", "Repaired", cost == null ? null : "Repair cost " + cost / 100 + " rupees", (String) r.get("status_name"), next.name(), text(b.get("note")), null);
		audit.publish("assets", "Asset", id, "REPAIRED", null, null);
		return detail(id);
	}

	@Transactional
	public Map<String, Object> retire(UUID id, Map<String, Object> b) {
		Map<String, Object> r = row(id, true);
		String reason = b.get("reason") == null ? "" : b.get("reason").toString().toUpperCase(Locale.ROOT);
		if (!RETIRE_REASONS.contains(reason)) {
			throw problem("reason", "REASON_REQUIRED", "Choose a reason: " + String.join(", ", RETIRE_REASONS));
		}
		if ("RETIRED".equals(r.get("system_key"))) {
			throw new CodedConflictException("NOT_ASSIGNABLE", r.get("tag") + " is already retired");
		}
		if (r.get("holder_id") != null) {
			throw new CodedConflictException("HAS_OPEN_ASSIGNMENT", r.get("tag") + " is assigned to " + r.get("h_name") + ". Recover it first.");
		}
		LocalDate on = b.get("retiredOn") == null ? support.today() : date(b.get("retiredOn"), "retiredOn");
		Status retired = support.statusByKey(KIND, "RETIRED");
		jdbc.sql("update asset set status_id = :s, retired_on = :d, retire_reason = :r, version = version + 1, updated_at = now(), updated_by = :by where id = :id")
				.param("s", retired.id()).param("d", on).param("r", reason).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		support.hardwareEvent(id, "RETIRED", "Retired", reason.toLowerCase(Locale.ROOT).replace('_', ' '), (String) r.get("status_name"), retired.name(), text(b.get("note")), null);
		audit.publish("assets", "Asset", id, "RETIRE", null, Map.of("reason", reason));
		return detail(id);
	}

	// ================================================================== files

	private List<Map<String, Object>> files(UUID id) {
		return jdbc.sql("select id, name, size_bytes, mime, uploaded_at from asset_file where asset_id = :a and deleted_at is null order by uploaded_at desc").param("a", id)
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2), "size", rs.getLong(3), "mime", String.valueOf(rs.getString(4)),
						"uploadedAt", rs.getObject(5, OffsetDateTime.class))).list();
	}

	@Transactional
	public Map<String, Object> addFile(UUID id, String name, String mime, byte[] bytes) {
		row(id, false);
		if (bytes == null || bytes.length == 0) {
			throw problem("file", "REQUIRED", "Choose a file");
		}
		if (bytes.length > 10 * 1024 * 1024) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File is larger than 10 MB");
		}
		String safe = name == null ? "file" : name.replaceAll("[\\\\/\\r\\n]", "_");
		String lower = safe.toLowerCase(Locale.ROOT);
		if (!(lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".txt") || lower.endsWith(".eml"))) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Attach a PDF, JPG, PNG, TXT or EML file");
		}
		UUID fileId = UUID.randomUUID();
		String key = currentUser.orgId() + "/assets/" + id + "/" + fileId;
		try {
			store.put(key, bytes);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved. Try again.");
		}
		jdbc.sql("insert into asset_file (id, asset_id, name, size_bytes, mime, storage_key, uploaded_by) values (:id, :a, :n, :s, :m, :k, :by)").param("id", fileId).param("a", id)
				.param("n", safe).param("s", bytes.length).param("m", mime).param("k", key).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		support.hardwareEvent(id, "FILE", "File attached", safe, null, null, null, null);
		return Map.of("id", fileId, "name", safe);
	}

	@Transactional
	public Object[] file(UUID id, UUID fileId) {
		row(id, false);
		Map<String, Object> f = jdbc.sql("select storage_key, name, mime from asset_file where id = :f and asset_id = :a and deleted_at is null").param("f", fileId).param("a", id)
				.query((rs, i) -> Map.<String, Object>of("k", rs.getString(1), "n", rs.getString(2), "m", String.valueOf(rs.getString(3)))).optional()
				.orElseThrow(() -> new NotFoundException("File " + fileId + " not found"));
		audit.publish("assets", "Asset", id, "VIEW_FILE", null, f.get("n"));
		try {
			return new Object[] {store.get((String) f.get("k")), f.get("n"), f.get("m")};
		}
		catch (IOException ex) {
			throw new NotFoundException("The file is no longer available");
		}
	}

	@Transactional
	public void deleteFile(UUID id, UUID fileId) {
		row(id, false);
		if (jdbc.sql("update asset_file set deleted_at = now() where id = :f and asset_id = :a and deleted_at is null").param("f", fileId).param("a", id).update() == 0) {
			throw new NotFoundException("File " + fileId + " not found");
		}
		support.hardwareEvent(id, "FILE", "File removed", null, null, null, null, null);
	}

	// ================================================================== helpers

	private void requireSerialFree(UUID org, UUID typeId, String serial, UUID except) {
		String existing = jdbc.sql("select tag from asset where org_id = :org and type_id = :t and lower(trim(serial)) = lower(trim(:s)) and deleted_at is null and (cast(:x as uuid) is null or id <> :x)")
				.param("org", org).param("t", typeId).param("s", serial).param("x", except, java.sql.Types.OTHER).query(String.class).optional().orElse(null);
		if (existing != null) {
			throw new StatusException(HttpStatus.CONFLICT, "SERIAL_TAKEN", "Serial " + serial + " already exists on " + existing, Map.of("existingTag", existing));
		}
	}

	private Map<String, Object> ownership(UUID id) {
		return jdbc.sql("select name, return_required, active from asset_ownership where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> Map.<String, Object>of("name", rs.getString(1), "returnRequired", rs.getBoolean(2), "active", rs.getBoolean(3))).optional()
				.filter(o -> (Boolean) o.get("active")).orElseThrow(() -> problem("ownershipId", "INVALID", "Choose an active ownership type"));
	}

	private void requireActiveEmployee(UUID id) {
		String st = jdbc.sql("select status from employee where id = :id and org_id = :org and archived_at is null").param("id", id).param("org", currentUser.orgId()).query(String.class)
				.optional().orElseThrow(() -> problem("employeeId", "EMPLOYEE_INACTIVE", "Choose a current employee"));
		if ("EXITED".equals(st)) {
			throw problem("employeeId", "EMPLOYEE_INACTIVE", "That person has left");
		}
	}

	private static LocalDate ownershipDate(Map<String, Object> data) {
		for (String k : new String[] {"Return date", "Lease end", "returnDate", "leaseEnd"}) {
			Object v = data.get(k);
			if (v != null && !v.toString().isBlank()) {
				try {
					return LocalDate.parse(v.toString());
				}
				catch (DateTimeParseException ex) {
					return null;
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String s) {
		try {
			return s == null ? Map.of() : json.readValue(s, Map.class);
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}

	static String text(Object o) {
		return o == null || o.toString().isBlank() ? null : o.toString().trim();
	}

	static UUID uuid(Object o, String field) {
		try {
			return UUID.fromString(String.valueOf(o).trim());
		}
		catch (IllegalArgumentException ex) {
			throw problem(field, "INVALID", "Choose a value from the list");
		}
	}

	static LocalDate date(Object o, String field) {
		if (o == null || o.toString().isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(o.toString().trim());
		}
		catch (DateTimeParseException ex) {
			throw problem(field, "INVALID_FORMAT", "Use the date format yyyy-mm-dd");
		}
	}

	static Long money(Object o) {
		if (o == null || o.toString().isBlank()) {
			return null;
		}
		try {
			long v = new BigDecimal(o.toString().trim()).longValueExact();
			if (v < 0) {
				throw problem("purchaseCost", "INVALID", "Cost cannot be negative");
			}
			return v;
		}
		catch (ArithmeticException | NumberFormatException ex) {
			throw problem("purchaseCost", "INVALID_FORMAT", "Enter the cost in paise as a whole number");
		}
	}
}
