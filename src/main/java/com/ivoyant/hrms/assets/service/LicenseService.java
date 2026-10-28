package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetIds;
import com.ivoyant.hrms.assets.service.AssetSupport.Status;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.LicenseSeatAssignedEvent;
import com.ivoyant.hrms.common.event.LicenseSeatRevokedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Software licences: one row per licence or contract, N seats, health worked out from dates and seat use. */
@Service
public class LicenseService {

	static final String KIND = "SOFTWARE";
	private static final String SPECS = "asset_software";
	private static final UUID ALL = new UUID(0, 0);

	/** A licence with everything the screens need, loaded in one query. */
	record Lic(UUID id, String lid, String name, String vendor, UUID categoryId, String categoryName, UUID typeId, String typeName, String billing, String unit, Integer seats,
			long costPerSeat, LocalDate termStart, LocalDate renewsOn, boolean autoRenew, UUID ownerId, String ownerCode, String ownerName, UUID statusId, String statusName,
			String statusKey, int statusColor, String notes, long version, int used, OffsetDateTime createdAt, UUID createdBy, OffsetDateTime updatedAt, UUID updatedBy) {

		boolean siteWide() {
			return seats == null;
		}

		long annualCost() {
			int n = seats == null ? 1 : seats;
			return switch (billing == null ? "" : billing) {
				case "Monthly" -> costPerSeat * n * 12;
				case "Annual" -> costPerSeat * n;
				default -> 0;
			};
		}

		long perSeatAnnual() {
			return switch (billing == null ? "" : billing) {
				case "Monthly" -> costPerSeat * 12;
				case "Annual" -> costPerSeat;
				default -> 0;
			};
		}
	}

	private static final String SELECT = """
			select l.id, l.lid, l.name, l.vendor, l.category_id, c.name, l.type_id, t.name, t.billing, t.unit, l.seats, l.cost_per_seat, l.term_start, l.renews_on, l.auto_renew,
			       l.owner_employee_id, o.employee_code, o.display_name, l.status_id, s.name, s.system_key, coalesce(s.color, 0), l.notes, l.version,
			       (select count(*) from license_seat x where x.license_id = l.id and x.revoked_on is null)::int, l.created_at, l.created_by, l.updated_at, l.updated_by
			from license l join asset_category c on c.id = l.category_id join asset_type t on t.id = l.type_id join employee o on o.id = l.owner_employee_id
			left join asset_status s on s.id = l.status_id where l.org_id = :org and l.deleted_at is null""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final AssetSupport support;
	private final AssetIds ids;
	private final SpecStore specs;
	private final Notifier notifier;
	private final ApplicationEventPublisher events;
	private final Audit audit;
	private final RequestLinks links;

	LicenseService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, AssetSupport support, AssetIds ids, SpecStore specs, Notifier notifier,
			ApplicationEventPublisher events, Audit audit, RequestLinks links) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.support = support;
		this.ids = ids;
		this.specs = specs;
		this.notifier = notifier;
		this.events = events;
		this.audit = audit;
		this.links = links;
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	private boolean money() {
		return access.has("assets", "EDIT");
	}

	private int[] thresholds() {
		return jdbc.sql("select assets_renewal_due_days, assets_underused_pct from org_settings where org_id = :org").param("org", currentUser.orgId())
				.query((rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}).optional().orElse(new int[] {45, 60});
	}

	// ================================================================== loading

	private Lic map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Lic(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class),
				rs.getString(8), rs.getString(9), rs.getString(10), (Integer) rs.getObject(11), rs.getLong(12), rs.getObject(13, LocalDate.class), rs.getObject(14, LocalDate.class),
				rs.getBoolean(15), rs.getObject(16, UUID.class), rs.getString(17), rs.getString(18), rs.getObject(19, UUID.class), rs.getString(20), rs.getString(21), rs.getInt(22),
				rs.getString(23), rs.getLong(24), rs.getInt(25), rs.getObject(26, OffsetDateTime.class), rs.getObject(27, UUID.class), rs.getObject(28, OffsetDateTime.class),
				rs.getObject(29, UUID.class));
	}

	List<Lic> all() {
		return jdbc.sql(SELECT + " order by lower(l.name)").param("org", currentUser.orgId()).query((rs, i) -> map(rs)).list();
	}

	Lic load(UUID id, boolean lock) {
		return jdbc.sql(SELECT + " and l.id = :id" + (lock ? " for update of l" : "")).param("org", currentUser.orgId()).param("id", id).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Licence " + id + " not found"));
	}

	Map<String, Object> state(Lic l, int[] th) {
		LocalDate today = support.today();
		String key;
		String label;
		String badge;
		if (l.statusId() != null && l.statusKey() != null && !"ACTIVE".equals(l.statusKey())) {
			key = "MANUAL";
			label = l.statusName().toLowerCase(Locale.ROOT);
			badge = "paused";
		}
		else if (l.renewsOn() != null && l.renewsOn().isBefore(today)) {
			key = "EXPIRED";
			label = "expired";
			badge = "failed";
		}
		else if (l.renewsOn() != null && ChronoUnit.DAYS.between(today, l.renewsOn()) <= th[0]) {
			key = "RENEWAL_DUE";
			label = "renewal due";
			badge = "degraded";
		}
		else if (!l.siteWide() && l.seats() > 0 && l.used() >= l.seats()) {
			key = "FULLY_USED";
			label = "fully used";
			badge = "running";
		}
		else if (!l.siteWide() && l.seats() > 0 && l.used() * 100 < (long) th[1] * l.seats()) {
			key = "UNDERUSED";
			label = "underused";
			badge = "pending";
		}
		else {
			key = "ACTIVE";
			label = "active";
			badge = "healthy";
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", key);
		m.put("label", label);
		m.put("badge", badge);
		return m;
	}

	private Map<String, Object> row(Lic l, int[] th, boolean withMoney) {
		LocalDate today = support.today();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", l.id());
		m.put("lid", l.lid());
		m.put("name", l.name());
		m.put("vendor", l.vendor());
		m.put("owner", Map.of("id", l.ownerId(), "code", l.ownerCode(), "name", l.ownerName()));
		m.put("category", Map.of("id", l.categoryId(), "name", l.categoryName(), "color", AssetSupport.typeColor(l.categoryId())));
		m.put("type", Map.of("id", l.typeId(), "name", l.typeName(), "billing", String.valueOf(l.billing()).toUpperCase(Locale.ROOT).replace("-", "_"), "unit", String.valueOf(l.unit())));
		m.put("seats", l.seats());
		m.put("used", l.used());
		m.put("free", l.siteWide() ? null : Math.max(0, l.seats() - l.used()));
		m.put("utilPct", l.siteWide() || l.seats() == 0 ? null : Math.round(100f * l.used() / l.seats()));
		if (withMoney) {
			m.put("annualCost", l.annualCost());
		}
		m.put("renewsOn", l.renewsOn());
		m.put("daysToRenew", l.renewsOn() == null ? null : ChronoUnit.DAYS.between(today, l.renewsOn()));
		m.put("state", state(l, th));
		Map<String, Object> actions = new LinkedHashMap<>();
		boolean free = l.siteWide() || l.used() < l.seats();
		actions.put("assignSeat", free);
		actions.put("addSeats", !l.siteWide() && !free);
		m.put("actions", actions);
		return m;
	}

	// ================================================================== lists

	@Transactional(readOnly = true)
	public Map<String, Object> list(String q, UUID categoryId, String state, UUID ownerId, int page, int size, String sort) {
		int[] th = thresholds();
		boolean withMoney = money();
		String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
		List<Lic> rows = new ArrayList<>(all().stream().filter(l -> categoryId == null || categoryId.equals(l.categoryId())).filter(l -> ownerId == null || ownerId.equals(l.ownerId()))
				.filter(l -> needle == null || needle.isEmpty() || (l.name() + " " + l.vendor() + " " + l.ownerName() + " " + l.lid()).toLowerCase(Locale.ROOT).contains(needle))
				.filter(l -> {
					if (state == null || state.isBlank() || state.equalsIgnoreCase("ALL")) {
						return true;
					}
					Map<String, Object> s = state(l, th);
					if (l.statusId() != null && l.statusId().toString().equalsIgnoreCase(state)) {
						return true;
					}
					return s.get("key").toString().equalsIgnoreCase(state);
				}).toList());
		Comparator<Lic> order = switch (sort == null ? "" : sort) {
			case "cost" -> Comparator.comparingLong(Lic::annualCost).reversed();
			case "renews" -> Comparator.comparing(Lic::renewsOn, Comparator.nullsLast(Comparator.naturalOrder()));
			case "used" -> Comparator.comparingInt(Lic::used).reversed();
			default -> Comparator.comparing(l -> l.name().toLowerCase(Locale.ROOT));
		};
		rows.sort(order);
		int pageSize = Math.max(1, Math.min(size <= 0 ? 25 : size, 100));
		int pg = Math.max(1, page);
		List<Map<String, Object>> items = rows.stream().skip((long) (pg - 1) * pageSize).limit(pageSize).map(l -> row(l, th, withMoney)).toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("page", pg);
		out.put("size", pageSize);
		out.put("total", rows.size());
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> available() {
		int[] th = thresholds();
		boolean withMoney = money();
		List<Lic> live = all().stream().filter(l -> !"EXPIRED".equals(state(l, th).get("key")) && !"MANUAL".equals(state(l, th).get("key"))).toList();
		Map<UUID, Map<String, Object>> groups = new LinkedHashMap<>();
		List<Map<String, Object>> full = new ArrayList<>();
		for (Lic l : live) {
			if (l.siteWide()) {
				continue;
			}
			int free = l.seats() - l.used();
			if (free <= 0) {
				full.add(row(l, th, withMoney));
				continue;
			}
			Map<String, Object> g = groups.computeIfAbsent(l.categoryId(), k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("category", Map.of("id", l.categoryId(), "name", l.categoryName()));
				m.put("freeSeats", 0);
				m.put("idle", 0);
				m.put("items", new ArrayList<Map<String, Object>>());
				return m;
			});
			g.put("freeSeats", (Integer) g.get("freeSeats") + free);
			if ("UNDERUSED".equals(state(l, th).get("key"))) {
				g.put("idle", (Integer) g.get("idle") + 1);
			}
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
			items.add(row(l, th, withMoney));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("summary", groups.values().stream().map(g -> Map.of("category", g.get("category"), "freeSeats", g.get("freeSeats"), "idle", g.get("idle"))).toList());
		out.put("groups", new ArrayList<>(groups.values()));
		out.put("fullyAllocated", full);
		return out;
	}

	@Transactional
	public Map<String, Object> dashboard(UUID categoryId) {
		int[] th = thresholds();
		List<Lic> scope = all().stream().filter(l -> categoryId == null || categoryId.equals(l.categoryId())).toList();
		LocalDate today = support.today();
		long totalSeats = scope.stream().filter(l -> !l.siteWide()).mapToLong(Lic::seats).sum();
		long usedSeats = scope.stream().filter(l -> !l.siteWide()).mapToLong(Lic::used).sum();
		long annual = scope.stream().mapToLong(Lic::annualCost).sum();
		long renewals = scope.stream().filter(l -> l.renewsOn() != null && !l.renewsOn().isBefore(today) && ChronoUnit.DAYS.between(today, l.renewsOn()) <= th[0]).count();
		long owners = scope.stream().map(Lic::ownerId).distinct().count();
		long cats = scope.stream().map(Lic::categoryId).distinct().count();
		boolean withMoney = money();
		List<Map<String, Object>> kpis = new ArrayList<>();
		kpis.add(HardwareService.kpi("total", "Total software assets", scope.size(), cats + " categories · " + owners + " owners"));
		kpis.add(HardwareService.kpi("assigned", "Assigned licenses", usedSeats + " / " + totalSeats, (totalSeats == 0 ? 0 : Math.round(100.0 * usedSeats / totalSeats)) + "% utilisation"));
		if (withMoney) {
			kpis.add(HardwareService.kpi("cost", "Annual cost", annual, annual / 12 + " per month"));
		}
		kpis.add(HardwareService.kpi("renewals", "Renewals due", renewals, "in the next " + th[0] + " days"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("kpis", kpis);
		Map<String, Long> byCat = scope.stream().collect(Collectors.groupingBy(Lic::categoryName, LinkedHashMap::new, Collectors.counting()));
		out.put("byCategory", byCat.entrySet().stream().map(e -> Map.<String, Object>of("label", e.getKey(), "n", e.getValue())).toList());
		Map<String, Long> byType = scope.stream().collect(Collectors.groupingBy(Lic::typeName, LinkedHashMap::new, Collectors.counting()));
		out.put("byType", byType.entrySet().stream().map(e -> Map.<String, Object>of("label", e.getKey(), "n", e.getValue())).toList());
		Map<String, Long> byState = scope.stream().collect(Collectors.groupingBy(l -> (String) state(l, th).get("label"), LinkedHashMap::new, Collectors.counting()));
		out.put("byState", byState.entrySet().stream().map(e -> Map.<String, Object>of("label", e.getKey(), "n", e.getValue())).toList());
		out.put("usage", usage(categoryId));
		List<Lic> underused = scope.stream().filter(l -> "UNDERUSED".equals(state(l, th).get("key"))).sorted(Comparator.comparingLong((Lic l) -> (l.seats() - l.used()) * l.perSeatAnnual()).reversed()).toList();
		Map<String, Object> under = new LinkedHashMap<>();
		if (withMoney) {
			under.put("annualSavings", underused.stream().mapToLong(l -> (l.seats() - l.used()) * l.perSeatAnnual()).sum());
		}
		under.put("top", underused.stream().limit(3).map(l -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", l.id());
			m.put("name", l.name());
			m.put("used", l.used());
			m.put("seats", l.seats());
			if (withMoney) {
				m.put("saving", (l.seats() - l.used()) * l.perSeatAnnual());
			}
			return m;
		}).toList());
		out.put("underused", under);
		out.put("alerts", alerts());
		out.put("activity", jdbc.sql("select e.title, e.at, a.display_name, a.first_name, a.last_name from license_event e left join employee a on a.id = e.actor_id where e.org_id = :org order by e.at desc limit 10")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("who", rs.getString(3) == null ? "System" : rs.getString(3));
					m.put("initials", rs.getString(3) == null ? "SY" : (rs.getString(4).substring(0, 1) + rs.getString(5).substring(0, 1)).toUpperCase());
					m.put("text", rs.getString(1));
					m.put("at", rs.getObject(2, OffsetDateTime.class));
					return m;
				}).list());
		return out;
	}

	private List<Map<String, Object>> alerts() {
		UUID me = currentUser.employeeId();
		if (me == null) {
			return List.of();
		}
		return jdbc.sql("select id, title, body, created_at from notification_inbox where employee_id = :e and type in ('LICENSE_RENEWAL', 'ASSET_ALERT') and read_at is null order by created_at desc limit 10")
				.param("e", me).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getLong(1));
					m.put("title", rs.getString(2));
					m.put("sub", rs.getString(3));
					m.put("severity", "warning");
					m.put("at", rs.getObject(4, OffsetDateTime.class));
					return m;
				}).list();
	}

	/** Seats used at the end of each of the last six months (seat history), against today's seats. */
	private List<Map<String, Object>> usage(UUID categoryId) {
		List<Map<String, Object>> out = new ArrayList<>();
		YearMonth now = YearMonth.from(support.today());
		for (int i = 5; i >= 0; i--) {
			YearMonth ym = now.minusMonths(i);
			LocalDate eom = i == 0 ? support.today() : ym.atEndOfMonth();
			Map<String, Object> m = jdbc.sql("""
					select coalesce(sum(l.seats) filter (where l.seats is not null), 0),
					       (select count(*) from license_seat s join license l2 on l2.id = s.license_id where l2.org_id = :org and l2.deleted_at is null and l2.seats is not null
					          and (cast(:cat as uuid) is null or l2.category_id = :cat) and s.assigned_on <= :eom and (s.revoked_on is null or s.revoked_on > :eom))
					from license l where l.org_id = :org and l.deleted_at is null and (cast(:cat as uuid) is null or l.category_id = :cat) and l.created_at::date <= :eom""")
					.param("org", currentUser.orgId()).param("cat", categoryId, java.sql.Types.OTHER).param("eom", eom).query((rs, n) -> {
						Map<String, Object> r = new LinkedHashMap<>();
						r.put("month", ym.toString());
						r.put("label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
						r.put("total", rs.getLong(1));
						r.put("used", rs.getLong(2));
						return r;
					}).single();
			out.add(m);
		}
		return out;
	}

	/** Writes this month's snapshot (all categories and each category) for the history table. */
	@Transactional
	public int snapshot() {
		LocalDate month = YearMonth.from(support.today()).atDay(1);
		List<Lic> all = all();
		int n = 0;
		Map<UUID, long[]> perCat = new LinkedHashMap<>();
		long[] totalAll = new long[2];
		for (Lic l : all) {
			if (l.siteWide()) {
				continue;
			}
			long[] c = perCat.computeIfAbsent(l.categoryId(), k -> new long[2]);
			c[0] += l.seats();
			c[1] += l.used();
			totalAll[0] += l.seats();
			totalAll[1] += l.used();
		}
		perCat.put(ALL, totalAll);
		for (Map.Entry<UUID, long[]> e : perCat.entrySet()) {
			jdbc.sql("""
					insert into license_usage_month (org_id, month, category_id, total_seats, used_seats) values (:org, :m, :c, :t, :u)
					on conflict (org_id, month, category_id) do update set total_seats = :t, used_seats = :u""").param("org", currentUser.orgId()).param("m", month)
					.param("c", e.getKey()).param("t", (int) e.getValue()[0]).param("u", (int) e.getValue()[1]).update();
			n++;
		}
		return n;
	}

	// ================================================================== detail

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID id) {
		Lic l = load(id, false);
		int[] th = thresholds();
		boolean withMoney = money();
		Map<String, Object> out = new LinkedHashMap<>(row(l, th, withMoney));
		out.put("termStart", l.termStart());
		out.put("autoRenew", l.autoRenew());
		out.put("notes", l.notes());
		out.put("status", l.statusId() == null ? null : Map.of("id", l.statusId(), "name", l.statusName(), "systemKey", String.valueOf(l.statusKey())));
		out.put("specs", specs.read(SPECS, id, categoryKey(l.categoryName())));
		if (withMoney) {
			Map<String, Object> cost = new LinkedHashMap<>();
			cost.put("perSeat", l.costPerSeat());
			cost.put("billing", l.billing());
			cost.put("annual", l.annualCost());
			cost.put("monthly", l.annualCost() / 12);
			out.put("cost", cost);
		}
		Map<String, Object> renewal = new LinkedHashMap<>();
		renewal.put("date", l.renewsOn());
		renewal.put("daysLeft", l.renewsOn() == null ? null : ChronoUnit.DAYS.between(support.today(), l.renewsOn()));
		renewal.put("termStart", l.termStart());
		if (l.termStart() != null && l.renewsOn() != null && l.renewsOn().isAfter(l.termStart())) {
			long total = ChronoUnit.DAYS.between(l.termStart(), l.renewsOn());
			renewal.put("pctElapsed", (int) Math.max(0, Math.min(100, 100 * ChronoUnit.DAYS.between(l.termStart(), support.today()) / total)));
		}
		renewal.put("autoRenew", l.autoRenew());
		out.put("renewal", renewal);
		Map<String, Object> admin = new LinkedHashMap<>();
		admin.put("createdAt", l.createdAt());
		admin.put("createdBy", support.employeeName(l.createdBy()));
		admin.put("updatedAt", l.updatedAt());
		admin.put("updatedBy", support.employeeName(l.updatedBy()));
		out.put("admin", admin);
		out.put("version", l.version());
		boolean edit = access.has("assets", "EDIT");
		out.put("can", Map.of("edit", edit, "assignSeat", edit && (l.siteWide() || l.used() < l.seats()), "addSeats", edit && !l.siteWide(), "renew", edit));
		return out;
	}

	static String categoryKey(String name) {
		return name.toLowerCase(Locale.ROOT).replace(' ', '_');
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> seats(UUID id, String q) {
		load(id, false);
		String needle = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
		return jdbc.sql("""
				select s.employee_id, e.employee_code, e.display_name, e.first_name, e.last_name, jt.name, d.name, s.assigned_on, e.photo_version
				from license_seat s join employee e on e.id = s.employee_id left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
				where s.license_id = :l and s.revoked_on is null and (cast(:q as text) is null or lower(e.display_name || ' ' || e.employee_code) like :q) order by lower(e.display_name)""")
				.param("l", id).param("q", needle).query((rs, i) -> {
					Map<String, Object> m = AssetSupport.card(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
							rs.getString(7), rs.getInt(9));
					m.put("assignedOn", rs.getObject(8, LocalDate.class));
					return m;
				}).list();
	}

	@Transactional(readOnly = true)
	public Object timeline(UUID id, String q, String kind, String order, int page, boolean csv) {
		load(id, false);
		List<String> kinds = switch (kind == null ? "ALL" : kind.toUpperCase(Locale.ROOT)) {
			case "SEAT" -> List.of("SEAT_ASSIGNED", "SEAT_REVOKED", "SEATS_CHANGED");
			case "RENEWAL" -> List.of("RENEWED", "AUTO_RENEW");
			case "EDIT" -> List.of("EDITED", "STATUS", "CREATED", "IMPORTED");
			default -> List.of();
		};
		boolean asc = "asc".equalsIgnoreCase(order);
		String sql = """
				select e.kind, e.title, e.description, e.prev_value, e.new_value, e.at, a.display_name, p.display_name
				from license_event e left join employee a on a.id = e.actor_id left join employee p on p.id = e.employee_id
				where e.license_id = :id and (:all or e.kind in (:kinds)) and (cast(:q as text) is null or lower(e.title || ' ' || coalesce(e.description, '')) like :q)
				order by e.at""" + (asc ? " asc" : " desc") + ", e.id" + (asc ? " asc" : " desc") + (csv ? "" : " limit 50 offset " + Math.max(0, page) * 50);
		List<Map<String, Object>> items = jdbc.sql(sql).param("id", id).param("all", kinds.isEmpty()).param("kinds", kinds.isEmpty() ? List.of("-") : kinds)
				.param("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%").query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("kind", rs.getString(1));
					m.put("title", rs.getString(2));
					m.put("description", rs.getString(3));
					m.put("previous", rs.getString(4));
					m.put("value", rs.getString(5));
					m.put("at", rs.getObject(6, OffsetDateTime.class));
					m.put("by", rs.getString(7));
					m.put("person", rs.getString(8));
					return m;
				}).list();
		if (!csv) {
			return Map.of("items", items, "count", items.size(), "page", Math.max(0, page));
		}
		StringBuilder sb = new StringBuilder("When,Kind,Title,Description,Previous,New,By\n");
		for (Map<String, Object> m : items) {
			sb.append(String.join(",", HardwareService.csv(m.get("at")), HardwareService.csv(m.get("kind")), HardwareService.csv(m.get("title")), HardwareService.csv(m.get("description")),
					HardwareService.csv(m.get("previous")), HardwareService.csv(m.get("value")), HardwareService.csv(m.get("by")))).append('\n');
		}
		return sb.toString();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> previewId(UUID typeId, String name) {
		UUID formatId = ids.formatFor(KIND, typeId).orElse(null);
		if (formatId == null) {
			return Map.of("lid", "");
		}
		String fmt = jdbc.sql("select fmt from asset_id_format where id = :id").param("id", formatId).query(String.class).single();
		String scope = AssetSupport.code(name == null ? "" : name);
		int next = jdbc.sql("select coalesce(max(last_value), 0) from asset_id_seq where format_id = :f and scope = :s").param("f", formatId).param("s", scope).query(Integer.class).single() + 1;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("#+").matcher(fmt.replace("{APP}", scope).replace("{TYPE}", scope));
		StringBuilder sb = new StringBuilder();
		boolean done = false;
		while (m.find()) {
			if (!done) {
				m.appendReplacement(sb, String.format("%0" + m.group().length() + "d", next));
				done = true;
			}
		}
		m.appendTail(sb);
		return Map.of("lid", sb.toString());
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> seatCandidates(UUID categoryId, String q) {
		Set<UUID> depts = new LinkedHashSet<>();
		if (categoryId != null) {
			jdbc.sql("select unnest(default_department_ids) from asset_category where id = :id and org_id = :org").param("id", categoryId).param("org", currentUser.orgId())
					.query((rs, i) -> depts.add(rs.getObject(1, UUID.class))).list();
		}
		String needle = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
		return jdbc.sql("""
				select e.id, e.employee_code, e.display_name, d.name, e.department_id from employee e left join department d on d.id = e.department_id
				where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED' and (cast(:q as text) is null or lower(e.display_name || ' ' || e.employee_code) like :q)
				order by lower(e.display_name) limit 100""").param("org", currentUser.orgId()).param("q", needle).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("code", rs.getString(2));
					m.put("name", rs.getString(3));
					m.put("department", rs.getString(4));
					m.put("suggested", depts.contains(rs.getObject(5, UUID.class)));
					return m;
				}).list();
	}

	// ================================================================== create / edit

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> create(Map<String, Object> b, boolean imported) {
		UUID org = currentUser.orgId();
		String name = HardwareService.text(b.get("name"));
		if (name == null) {
			throw problem("name", "REQUIRED", "Application is required");
		}
		UUID categoryId = HardwareService.uuid(b.get("categoryId"), "categoryId");
		String categoryName = jdbc.sql("select name from asset_category where id = :id and org_id = :org and kind = 'SOFTWARE' and active").param("id", categoryId).param("org", org)
				.query(String.class).optional().orElseThrow(() -> problem("categoryId", "INVALID", "Choose a software category"));
		UUID typeId = HardwareService.uuid(b.get("typeId"), "typeId");
		Map<String, Object> type = jdbc.sql("select name, billing, unit from asset_type where id = :id and org_id = :org and kind = 'SOFTWARE' and active").param("id", typeId).param("org", org)
				.query((rs, i) -> Map.<String, Object>of("name", rs.getString(1), "billing", String.valueOf(rs.getString(2)), "unit", String.valueOf(rs.getString(3)))).optional()
				.orElseThrow(() -> problem("typeId", "INVALID", "Choose a license type"));
		String billing = (String) type.get("billing");
		boolean siteWide = "Site-wide".equals(type.get("unit"));
		List<UUID> holders = new ArrayList<>();
		if (b.get("holders") instanceof List<?> hl) {
			for (Object o : hl) {
				holders.add(HardwareService.uuid(o, "holders"));
			}
		}
		Integer seats = null;
		if (!siteWide) {
			try {
				seats = b.get("seats") == null ? null : Integer.valueOf(b.get("seats").toString().trim());
			}
			catch (NumberFormatException ex) {
				throw problem("seats", "INVALID_FORMAT", "Seats is a whole number");
			}
			if (seats == null || seats < 1) {
				throw problem("seats", "REQUIRED", "Seats is required");
			}
			if (holders.size() > seats) {
				throw problem("seats", "SEATS_BELOW_USED", "There are more seat holders than seats");
			}
		}
		long perSeat = b.get("costPerSeat") == null ? 0 : HardwareService.money(b.get("costPerSeat"));
		if (!"Free".equals(billing) && perSeat <= 0) {
			throw problem("costPerSeat", "REQUIRED", "Enter the price per seat");
		}
		LocalDate termStart = HardwareService.date(b.get("termStart"), "termStart");
		LocalDate renewsOn = HardwareService.date(b.get("renewsOn"), "renewsOn");
		if (renewsOn == null && !("One-time".equals(billing) || "Free".equals(billing))) {
			throw problem("renewsOn", "RENEWAL_REQUIRED", "Enter the renewal date");
		}
		if (termStart != null && renewsOn != null && termStart.isAfter(renewsOn)) {
			throw problem("termStart", "INVALID", "The contract start is after the renewal date");
		}
		UUID ownerId = HardwareService.uuid(b.get("ownerId") != null ? b.get("ownerId") : b.get("ownerEmployeeId"), "ownerId");
		requireActive(ownerId, "ownerId");
		UUID statusId = b.get("statusId") == null || b.get("statusId").toString().isBlank() ? null : HardwareService.uuid(b.get("statusId"), "statusId");
		if (statusId != null && !KIND.equals(support.status(statusId).kind())) {
			throw problem("statusId", "INVALID", "Choose a software status");
		}
		for (UUID h : holders) {
			requireActive(h, "holders");
		}
		if (jdbc.sql("select count(*) from license where org_id = :org and lower(name) = lower(:n) and type_id = :t and deleted_at is null").param("org", org).param("n", name).param("t", typeId)
				.query(Long.class).single() > 0) {
			throw new CodedConflictException("LICENSE_EXISTS", name + " already has a " + type.get("name") + " licence");
		}
		UUID formatId = ids.formatFor(KIND, typeId).orElseThrow(() -> new CodedConflictException("NO_ID_FORMAT", "No licence ID format is set up for " + type.get("name") + " licences"));
		String lid = HardwareService.text(b.get("lid")) != null && imported ? HardwareService.text(b.get("lid")) : ids.next(formatId, AssetSupport.code(name));
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into license (id, org_id, lid, name, vendor, category_id, type_id, seats, cost_per_seat, term_start, renews_on, auto_renew, owner_employee_id, status_id, notes, created_by)
				values (:id, :org, :lid, :name, :vendor, :cat, :type, :seats, :cost, :ts, :ro, :ar, :owner, :status, :notes, :by)""").param("id", id).param("org", org).param("lid", lid)
				.param("name", name).param("vendor", HardwareService.text(b.get("vendor"))).param("cat", categoryId).param("type", typeId).param("seats", seats, java.sql.Types.INTEGER)
				.param("cost", "Free".equals(billing) ? 0L : perSeat).param("ts", termStart).param("ro", renewsOn).param("ar", Boolean.TRUE.equals(b.get("autoRenew"))).param("owner", ownerId)
				.param("status", statusId, java.sql.Types.OTHER).param("notes", HardwareService.text(b.get("notes"))).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		specs.write(SPECS, id, categoryKey(categoryName), b.get("specs") instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of(), false);
		support.licenseEvent(id, imported ? "IMPORTED" : "CREATED", imported ? "Imported" : "Licence created", lid + " · " + name, null, null, null);
		audit.created("assets", "License", id, Map.of("lid", lid, "name", name));
		if (!holders.isEmpty()) {
			assignInternal(id, holders, null, support.today());
		}
		return detail(id);
	}

	private void requireActive(UUID employeeId, String field) {
		String st = jdbc.sql("select status from employee where id = :id and org_id = :org and archived_at is null").param("id", employeeId).param("org", currentUser.orgId())
				.query(String.class).optional().orElseThrow(() -> problem(field, "EMPLOYEE_INACTIVE", "Choose a current employee"));
		if ("EXITED".equals(st)) {
			throw problem(field, "EMPLOYEE_INACTIVE", "That person has left");
		}
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> update(UUID id, Map<String, Object> b, String ifMatch) {
		Lic l = load(id, true);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(l.version()))) {
			throw new StaleException("This licence was changed by someone else. Reload and try again.");
		}
		List<String> diffs = new ArrayList<>();
		String name = b.containsKey("name") ? HardwareService.text(b.get("name")) : l.name();
		if (name == null) {
			throw problem("name", "REQUIRED", "Application is required");
		}
		if (!name.equals(l.name())) {
			diffs.add("Name: " + l.name() + " → " + name);
		}
		Integer seats = l.seats();
		if (b.containsKey("seats") && !l.siteWide()) {
			try {
				seats = Integer.valueOf(String.valueOf(b.get("seats")).trim());
			}
			catch (NumberFormatException ex) {
				throw problem("seats", "INVALID_FORMAT", "Seats is a whole number");
			}
			if (seats < l.used()) {
				throw problem("seats", "SEATS_BELOW_USED", "Seats cannot be fewer than the " + l.used() + " already in use");
			}
			if (!seats.equals(l.seats())) {
				diffs.add("Seats: " + l.seats() + " → " + seats);
			}
		}
		long perSeat = b.containsKey("costPerSeat") ? HardwareService.money(b.get("costPerSeat")) : l.costPerSeat();
		if (perSeat != l.costPerSeat()) {
			diffs.add("Price per seat changed");
		}
		LocalDate termStart = b.containsKey("termStart") ? HardwareService.date(b.get("termStart"), "termStart") : l.termStart();
		LocalDate renewsOn = b.containsKey("renewsOn") ? HardwareService.date(b.get("renewsOn"), "renewsOn") : l.renewsOn();
		if (renewsOn == null && !("One-time".equals(l.billing()) || "Free".equals(l.billing()))) {
			throw problem("renewsOn", "RENEWAL_REQUIRED", "Enter the renewal date");
		}
		if (termStart != null && renewsOn != null && termStart.isAfter(renewsOn)) {
			throw problem("termStart", "INVALID", "The contract start is after the renewal date");
		}
		if (!java.util.Objects.equals(renewsOn, l.renewsOn())) {
			diffs.add("Renews on: " + l.renewsOn() + " → " + renewsOn);
		}
		UUID ownerId = b.containsKey("ownerId") ? HardwareService.uuid(b.get("ownerId"), "ownerId") : l.ownerId();
		if (!ownerId.equals(l.ownerId())) {
			requireActive(ownerId, "ownerId");
			diffs.add("Owner: " + l.ownerName() + " → " + support.employeeName(ownerId));
		}
		UUID statusId = l.statusId();
		if (b.containsKey("statusId")) {
			statusId = b.get("statusId") == null || b.get("statusId").toString().isBlank() ? null : HardwareService.uuid(b.get("statusId"), "statusId");
			if (statusId != null && !KIND.equals(support.status(statusId).kind())) {
				throw problem("statusId", "INVALID", "Choose a software status");
			}
			if (!java.util.Objects.equals(statusId, l.statusId())) {
				diffs.add("Status: " + (l.statusName() == null ? "automatic" : l.statusName()) + " → " + (statusId == null ? "automatic" : support.status(statusId).name()));
			}
		}
		boolean autoRenew = b.containsKey("autoRenew") ? Boolean.TRUE.equals(b.get("autoRenew")) : l.autoRenew();
		jdbc.sql("""
				update license set name = :name, vendor = :vendor, seats = :seats, cost_per_seat = :cost, term_start = :ts, renews_on = :ro, auto_renew = :ar, owner_employee_id = :owner,
				  status_id = :status, notes = :notes, version = version + 1, updated_at = now(), updated_by = :by where id = :id""").param("name", name)
				.param("vendor", b.containsKey("vendor") ? HardwareService.text(b.get("vendor")) : l.vendor()).param("seats", seats, java.sql.Types.INTEGER).param("cost", "Free".equals(l.billing()) ? 0L : perSeat)
				.param("ts", termStart).param("ro", renewsOn).param("ar", autoRenew).param("owner", ownerId).param("status", statusId, java.sql.Types.OTHER)
				.param("notes", b.containsKey("notes") ? HardwareService.text(b.get("notes")) : l.notes()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		if (b.get("specs") instanceof Map<?, ?> sm) {
			specs.write(SPECS, id, categoryKey(l.categoryName()), (Map<String, Object>) sm, true);
			diffs.add("Details updated");
		}
		if (!diffs.isEmpty()) {
			support.licenseEvent(id, "EDITED", "Licence edited", String.join("; ", diffs), null, null, null);
			audit.updated("assets", "License", id, Map.of("version", l.version()), Map.of("changes", diffs));
		}
		return detail(id);
	}

	@Transactional
	public void delete(UUID id) {
		Lic l = load(id, true);
		if (l.used() > 0) {
			throw new CodedConflictException("HAS_OPEN_ASSIGNMENT", l.name() + " still has " + l.used() + " seat holders. Revoke the seats first.");
		}
		jdbc.sql("update license set deleted_at = now(), updated_at = now(), updated_by = :by where id = :id").param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		audit.deleted("assets", "License", id, Map.of("lid", l.lid()));
	}

	// ================================================================== seats

	@Transactional
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> assignSeats(UUID id, Map<String, Object> b) {
		List<UUID> employees = new ArrayList<>();
		if (b.get("employeeIds") instanceof List<?> l) {
			for (Object o : l) {
				employees.add(HardwareService.uuid(o, "employeeIds"));
			}
		}
		if (employees.isEmpty()) {
			throw problem("employeeIds", "REQUIRED", "Choose at least one person");
		}
		UUID requestId = b.get("requestId") == null ? null : HardwareService.uuid(b.get("requestId"), "requestId");
		assignInternal(id, employees, requestId, support.today());
		return seats(id, null);
	}

	private void assignInternal(UUID id, List<UUID> employees, UUID requestId, LocalDate on) {
		Lic l = load(id, true);
		Set<UUID> unique = new LinkedHashSet<>(employees);
		for (UUID e : unique) {
			requireActive(e, "employeeIds");
			if (jdbc.sql("select count(*) from license_seat where license_id = :l and employee_id = :e and revoked_on is null").param("l", id).param("e", e).query(Long.class).single() > 0) {
				throw new CodedConflictException("SEAT_EXISTS", support.employeeName(e) + " already has a seat on " + l.name());
			}
		}
		if (!l.siteWide() && l.seats() - l.used() < unique.size()) {
			throw new CodedConflictException("NO_FREE_SEATS", l.name() + " has " + Math.max(0, l.seats() - l.used()) + " free seats. Add seats first.");
		}
		for (UUID e : unique) {
			UUID seatId = UUID.randomUUID();
			jdbc.sql("insert into license_seat (id, license_id, employee_id, assigned_on, request_id, assigned_by) values (:id, :l, :e, :d, :r, :by)").param("id", seatId).param("l", id)
					.param("e", e).param("d", on).param("r", requestId, java.sql.Types.OTHER).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
			support.licenseEvent(id, "SEAT_ASSIGNED", "Seat assigned to " + support.employeeName(e), null, null, null, e);
			links.linkSeat(e, id, seatId, requestId);
			notifier.inApp(List.of(e), "ASSET_ASSIGNED", "You now have a " + l.name() + " licence", l.lid(), "/me/assets");
			events.publishEvent(new LicenseSeatAssignedEvent(id, e));
		}
		audit.publish("assets", "License", id, "ASSIGN_SEATS", null, Map.of("count", unique.size()));
	}

	@Transactional
	public List<Map<String, Object>> revokeSeat(UUID id, UUID employeeId, String reason) {
		Lic l = load(id, true);
		int n = jdbc.sql("update license_seat set revoked_on = greatest(assigned_on, :d), revoked_by = :by where license_id = :l and employee_id = :e and revoked_on is null")
				.param("d", support.today()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("l", id).param("e", employeeId).update();
		if (n == 0) {
			throw new NotFoundException("That person does not have a seat on " + l.name());
		}
		support.licenseEvent(id, "SEAT_REVOKED", reason == null ? "Seat revoked from " + support.employeeName(employeeId) : reason, null, null, null, employeeId);
		events.publishEvent(new LicenseSeatRevokedEvent(id, employeeId));
		audit.publish("assets", "License", id, "REVOKE_SEAT", null, Map.of("employee", employeeId.toString()));
		return seats(id, null);
	}

	/** Takes back every seat of a person who left (the exit job and the exit event). */
	@Transactional
	public int reclaimSeats(UUID employeeId) {
		List<UUID> licenses = jdbc.sql("select license_id from license_seat where employee_id = :e and revoked_on is null").param("e", employeeId).query(UUID.class).list();
		for (UUID lid : licenses) {
			jdbc.sql("update license_seat set revoked_on = greatest(assigned_on, current_date) where license_id = :l and employee_id = :e and revoked_on is null").param("l", lid).param("e", employeeId).update();
			support.licenseEvent(lid, "SEAT_REVOKED", "Seat reclaimed from " + support.employeeName(employeeId) + " (exit)", null, null, null, employeeId);
			events.publishEvent(new LicenseSeatRevokedEvent(lid, employeeId));
		}
		return licenses.size();
	}

	@Transactional
	public Map<String, Object> addSeats(UUID id, Map<String, Object> b) {
		Lic l = load(id, true);
		if (l.siteWide()) {
			throw problem("count", "INVALID", l.name() + " is a site-wide licence and has no seat limit");
		}
		int count;
		try {
			count = Integer.parseInt(String.valueOf(b.get("count")).trim());
		}
		catch (NumberFormatException ex) {
			throw problem("count", "INVALID_FORMAT", "Enter how many seats to add");
		}
		if (count < 1 || count > 10000) {
			throw problem("count", "INVALID", "Add between 1 and 10000 seats");
		}
		jdbc.sql("update license set seats = seats + :n, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("n", count)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		support.licenseEvent(id, "SEATS_CHANGED", count + " seats added", HardwareService.text(b.get("note")), String.valueOf(l.seats()), String.valueOf(l.seats() + count), null);
		audit.publish("assets", "License", id, "ADD_SEATS", null, Map.of("count", count));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> renew(UUID id, Map<String, Object> b) {
		Lic l = load(id, true);
		if ("One-time".equals(l.billing()) || "Free".equals(l.billing()) || l.renewsOn() == null) {
			throw problem(null, "INVALID", l.name() + " has no renewal date to move");
		}
		LocalDate newDate = HardwareService.date(b.get("renewsOn"), "renewsOn");
		if (newDate == null) {
			newDate = "Monthly".equals(l.billing()) ? l.renewsOn().plusMonths(1) : l.renewsOn().plusYears(1);
		}
		if (!newDate.isAfter(l.renewsOn())) {
			throw problem("renewsOn", "INVALID", "The new renewal date must be after " + l.renewsOn());
		}
		long perSeat = b.get("costPerSeat") == null ? l.costPerSeat() : HardwareService.money(b.get("costPerSeat"));
		Integer seats = l.seats();
		if (b.get("seats") != null && !l.siteWide()) {
			seats = Integer.valueOf(String.valueOf(b.get("seats")).trim());
			if (seats < l.used()) {
				throw problem("seats", "SEATS_BELOW_USED", "Seats cannot be fewer than the " + l.used() + " already in use");
			}
		}
		jdbc.sql("update license set term_start = renews_on, renews_on = :ro, cost_per_seat = :c, seats = :s, version = version + 1, updated_at = now(), updated_by = :by where id = :id")
				.param("ro", newDate).param("c", perSeat).param("s", seats, java.sql.Types.INTEGER).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		support.licenseEvent(id, "RENEWED", "Renewed to " + newDate, null, String.valueOf(l.renewsOn()), String.valueOf(newDate), null);
		audit.publish("assets", "License", id, "RENEW", null, Map.of("renewsOn", newDate.toString()));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> autoRenew(UUID id, boolean on) {
		Lic l = load(id, true);
		if (l.autoRenew() != on) {
			jdbc.sql("update license set auto_renew = :a, version = version + 1, updated_at = now(), updated_by = :by where id = :id").param("a", on)
					.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
			support.licenseEvent(id, "AUTO_RENEW", "Auto-renew turned " + (on ? "on" : "off"), null, String.valueOf(l.autoRenew()), String.valueOf(on), null);
		}
		return detail(id);
	}
}
