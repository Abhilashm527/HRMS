package com.ivoyant.hrms.performance.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Performance reviews. HR (Performance edit) creates a cycle and launches it, which opens a review for everyone active with their manager
 * as reviewer. Each person writes a self review; the reviewer writes theirs and rates 1–5; HR can set a final rating when calibrating,
 * then closes the cycle. People see their own reviews once the cycle closes. Goals belong to a person; they and their manager edit them.
 */
@Service
public class PerformanceService {

	public static final List<String> RATINGS = List.of("Below expectations", "Partially meets", "Meets expectations", "Exceeds expectations", "Exceptional");
	static final List<String> SELF_KEYS = List.of("achievements", "challenges", "growth");
	static final List<String> MANAGER_KEYS = List.of("strengths", "improvements", "summary");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Notifier notifier;
	private final Audit audit;
	private final JsonMapper json;

	PerformanceService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Notifier notifier, Audit audit, JsonMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.notifier = notifier;
		this.audit = audit;
		this.json = json;
	}

	// ================================================================== cycles (HR)

	@Transactional(readOnly = true)
	public Map<String, Object> cycles() {
		List<Map<String, Object>> rows = jdbc.sql("""
				select c.id, c.name, c.period_start, c.period_end, c.self_due, c.manager_due, c.status,
				       count(r.id), count(r.manager_submitted_at)
				from review_cycle c left join review r on r.cycle_id = c.id where c.org_id = :org group by c.id order by c.period_end desc""").param("org", org())
				.query((rs, i) -> {
					Map<String, Object> m = cycleMap(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
							rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getString(7));
					m.put("reviews", rs.getLong(8));
					m.put("done", rs.getLong(9));
					return m;
				}).list();
		return Map.of("items", rows, "canManage", hr(), "ratings", RATINGS);
	}

	/** Body: {@code name, periodStart, periodEnd, selfDue, managerDue}. */
	@Transactional
	public Map<String, Object> createCycle(Map<String, Object> b) {
		requireHr();
		String name = str(b.get("name"));
		if (name == null || name.isBlank()) {
			throw problem("name", "Name the cycle, e.g. H2 2026 review");
		}
		LocalDate ps = date(b.get("periodStart"), "periodStart");
		LocalDate pe = date(b.get("periodEnd"), "periodEnd");
		LocalDate sd = date(b.get("selfDue"), "selfDue");
		LocalDate md = date(b.get("managerDue"), "managerDue");
		if (pe.isBefore(ps)) {
			throw problem("periodEnd", "The period must end after it starts");
		}
		if (md.isBefore(sd)) {
			throw problem("managerDue", "Manager reviews are due after self reviews");
		}
		UUID id = jdbc.sql("""
				insert into review_cycle (org_id, name, period_start, period_end, self_due, manager_due, created_by) values (:org, :n, :ps, :pe, :sd, :md, :by) returning id""")
				.param("org", org()).param("n", name.strip()).param("ps", ps).param("pe", pe).param("sd", sd).param("md", md).param("by", me()).query(UUID.class).single();
		audit.publish("performance", "ReviewCycle", id, "CREATE", null, Map.of("name", name));
		return Map.of("id", id);
	}

	/** Opens a review for everyone active, with their manager as reviewer, and tells them. */
	@Transactional
	public Map<String, Object> launch(UUID id) {
		requireHr();
		Map<String, Object> c = cycleRow(id);
		if (!"DRAFT".equals(c.get("status"))) {
			throw new ConflictException("This cycle has already been launched");
		}
		int n = jdbc.sql("""
				insert into review (cycle_id, employee_id, reviewer_id)
				select :c, e.id, e.manager_id from employee e where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED' and e.joining_date <= :pe
				on conflict do nothing""").param("c", id).param("org", org()).param("pe", c.get("periodEnd")).update();
		jdbc.sql("update review_cycle set status = 'ACTIVE', launched_at = now() where id = :c").param("c", id).update();
		List<UUID> people = jdbc.sql("select employee_id from review where cycle_id = :c").param("c", id).query(UUID.class).list();
		notifier.inApp(people, "PERFORMANCE", "Time for your self review", c.get("name") + " · due " + c.get("selfDue"), "/me/performance");
		audit.publish("performance", "ReviewCycle", id, "LAUNCH", null, Map.of("reviews", n));
		return Map.of("reviews", n);
	}

	@Transactional
	public void close(UUID id) {
		requireHr();
		Map<String, Object> c = cycleRow(id);
		if (!"ACTIVE".equals(c.get("status"))) {
			throw new ConflictException("Only an active cycle can be closed");
		}
		jdbc.sql("update review_cycle set status = 'CLOSED', closed_at = now() where id = :c").param("c", id).update();
		List<UUID> people = jdbc.sql("select employee_id from review where cycle_id = :c and manager_submitted_at is not null").param("c", id).query(UUID.class).list();
		notifier.inApp(people, "PERFORMANCE", "Your review is ready", c.get("name") + " · see your manager's feedback", "/me/performance");
		audit.publish("performance", "ReviewCycle", id, "CLOSE", null, Map.of("name", c.get("name")));
	}

	@Transactional
	public void deleteCycle(UUID id) {
		requireHr();
		if (!"DRAFT".equals(cycleRow(id).get("status"))) {
			throw new ConflictException("Only a draft cycle can be deleted");
		}
		jdbc.sql("delete from review_cycle where id = :c").param("c", id).update();
	}

	/** Reminds everyone whose part is still open: self reviews to the person, manager reviews to the reviewer. */
	@Transactional
	public Map<String, Object> remind(UUID id) {
		requireHr();
		Map<String, Object> c = cycleRow(id);
		List<UUID> self = jdbc.sql("select employee_id from review where cycle_id = :c and self_submitted_at is null").param("c", id).query(UUID.class).list();
		List<UUID> mgrs = jdbc.sql("select distinct reviewer_id from review where cycle_id = :c and manager_submitted_at is null and reviewer_id is not null").param("c", id)
				.query(UUID.class).list();
		notifier.inAppBatched(self, "Your self review is waiting", c.get("name") + " · due " + c.get("selfDue"), "/me/performance", 720);
		notifier.inAppBatched(mgrs, "Reviews waiting for you", c.get("name") + " · due " + c.get("managerDue"), "/performance", 720);
		return Map.of("self", self.size(), "managers", mgrs.size());
	}

	/** The cycle's dashboard: completion, rating distribution and every review the caller may see (HR: all; others: the ones they review). */
	@Transactional(readOnly = true)
	public Map<String, Object> cycle(UUID id) {
		Map<String, Object> c = cycleRow(id);
		boolean hr = hr();
		if (!hr && !access.has("performance", "VIEW")) {
			throw new ForbiddenException("Your role can't see reviews");
		}
		List<Map<String, Object>> rows = jdbc.sql("""
				select r.id, e.id, e.display_name, jt.name, d.name, m.display_name, r.reviewer_id, r.self_submitted_at, r.manager_submitted_at, r.self_rating, r.manager_rating,
				       r.final_rating
				from review r join employee e on e.id = r.employee_id left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
				left join employee m on m.id = r.reviewer_id where r.cycle_id = :c and (:hr or r.reviewer_id = :me) order by lower(e.display_name)""").param("c", id)
				.param("hr", hr).param("me", me()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("employee", Map.of("id", rs.getObject(2, UUID.class), "name", rs.getString(3), "title", nz(rs.getString(4)), "department", nz(rs.getString(5))));
					m.put("reviewer", rs.getString(6));
					m.put("reviewerIsMe", me().equals(rs.getObject(7, UUID.class)));
					m.put("selfDone", rs.getObject(8) != null);
					m.put("managerDone", rs.getObject(9) != null);
					m.put("selfRating", rs.getObject(10));
					m.put("managerRating", rs.getObject(11));
					Integer fin = (Integer) rs.getObject(12);
					m.put("finalRating", fin);
					Integer shown = fin != null ? fin : (Integer) rs.getObject(11);
					m.put("rating", shown == null ? null : RATINGS.get(shown - 1));
					return m;
				}).list();
		long done = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("managerDone"))).count();
		int[] dist = new int[5];
		for (Map<String, Object> r : rows) {
			Integer v = (Integer) (r.get("finalRating") != null ? r.get("finalRating") : r.get("managerRating"));
			if (v != null) {
				dist[v - 1]++;
			}
		}
		List<Map<String, Object>> d = new ArrayList<>();
		for (int i = 4; i >= 0; i--) {
			d.add(Map.of("label", RATINGS.get(i), "value", i + 1, "n", dist[i]));
		}
		c.put("rows", rows);
		c.put("total", rows.size());
		c.put("done", done);
		c.put("selfPending", rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("selfDone"))).count());
		c.put("managerPending", rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("managerDone"))).count());
		c.put("distribution", d);
		c.put("canManage", hr);
		return c;
	}

	// ================================================================== one review

	@Transactional(readOnly = true)
	public Map<String, Object> review(UUID id) {
		Map<String, Object> r = reviewRow(id);
		boolean hr = hr();
		boolean mine = me().equals(r.get("employeeId"));
		boolean reviewer = me().equals(r.get("reviewerId"));
		if (!hr && !mine && !reviewer) {
			throw new ForbiddenException("You can't see this review");
		}
		String status = (String) r.get("cycleStatus");
		// the person sees their manager's part only once the cycle closes
		if (mine && !hr && !reviewer && !"CLOSED".equals(status)) {
			r.put("managerAnswers", Map.of());
			r.put("managerRating", null);
			r.put("finalRating", null);
			r.put("finalNote", null);
		}
		// a reviewer sees the self review once it's submitted
		if (!mine && !hr && r.get("selfSubmittedAt") == null) {
			r.put("selfAnswers", Map.of());
			r.put("selfRating", null);
		}
		boolean active = "ACTIVE".equals(status);
		r.put("can", Map.of("self", mine && active && r.get("selfSubmittedAt") == null, "manager", reviewer && active && r.get("managerSubmittedAt") == null, "calibrate",
				hr && !"DRAFT".equals(status)));
		r.put("ratings", RATINGS);
		r.put("goals", goalsOf((UUID) r.get("employeeId")));
		r.remove("reviewerId");
		return r;
	}

	/** Body: {@code answers: {...}, rating, submit}. Saves a draft unless {@code submit} is true. */
	@Transactional
	public Map<String, Object> saveSelf(UUID id, Map<String, Object> b) {
		Map<String, Object> r = reviewRow(id);
		if (!me().equals(r.get("employeeId"))) {
			throw new ForbiddenException("This isn't your review");
		}
		requireOpen(r, "selfSubmittedAt");
		boolean submit = Boolean.TRUE.equals(b.get("submit"));
		Map<String, String> answers = answers(b.get("answers"), SELF_KEYS, submit);
		Integer rating = rating(b.get("rating"), submit);
		jdbc.sql("update review set self_answers = cast(:a as jsonb), self_rating = :r, self_submitted_at = case when :s then now() else null end where id = :id")
				.param("a", json.writeValueAsString(answers)).param("r", rating, java.sql.Types.INTEGER).param("s", submit).param("id", id).update();
		if (submit && r.get("reviewerId") != null) {
			notifier.inApp(List.of((UUID) r.get("reviewerId")), "PERFORMANCE", "Self review submitted", r.get("employeeName") + " · ready for your review", "/performance");
		}
		return review(id);
	}

	@Transactional
	public Map<String, Object> saveManager(UUID id, Map<String, Object> b) {
		Map<String, Object> r = reviewRow(id);
		if (!me().equals(r.get("reviewerId"))) {
			throw new ForbiddenException("You aren't this person's reviewer");
		}
		requireOpen(r, "managerSubmittedAt");
		boolean submit = Boolean.TRUE.equals(b.get("submit"));
		Map<String, String> answers = answers(b.get("answers"), MANAGER_KEYS, submit);
		Integer rating = rating(b.get("rating"), submit);
		jdbc.sql("update review set manager_answers = cast(:a as jsonb), manager_rating = :r, manager_submitted_at = case when :s then now() else null end where id = :id")
				.param("a", json.writeValueAsString(answers)).param("r", rating, java.sql.Types.INTEGER).param("s", submit).param("id", id).update();
		if (submit) {
			audit.publish("performance", "Review", id, "MANAGER_SUBMIT", null, Map.of("rating", rating));
		}
		return review(id);
	}

	/** HR: {@code rating} (1–5, or null to use the manager's) and {@code note}. */
	@Transactional
	public Map<String, Object> calibrate(UUID id, Map<String, Object> b) {
		requireHr();
		Map<String, Object> r = reviewRow(id);
		if ("DRAFT".equals(r.get("cycleStatus"))) {
			throw new ConflictException("Launch the cycle first");
		}
		Object v = b.get("rating");
		Integer rating = v == null || str(v).isBlank() ? null : rating(v, true);
		String note = str(b.get("note"));
		jdbc.sql("update review set final_rating = :r, final_note = :n where id = :id").param("r", rating, java.sql.Types.INTEGER)
				.param("n", note == null || note.isBlank() ? null : note.strip(), java.sql.Types.VARCHAR).param("id", id).update();
		audit.publish("performance", "Review", id, "CALIBRATE", null, Map.of("rating", rating == null ? "manager's" : rating));
		return review(id);
	}

	// ================================================================== self service & goals

	@Transactional(readOnly = true)
	public Map<String, Object> mine() {
		UUID me = currentUser.requireEmployeeId();
		List<Map<String, Object>> reviews = jdbc.sql("""
				select r.id, c.name, c.status, c.self_due, c.period_end, r.self_submitted_at, r.manager_submitted_at
				from review r join review_cycle c on c.id = r.cycle_id where r.employee_id = :e and c.status <> 'DRAFT' order by c.period_end desc""").param("e", me)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("cycle", rs.getString(2));
					m.put("status", rs.getString(3));
					m.put("selfDue", rs.getObject(4, LocalDate.class));
					m.put("periodEnd", rs.getObject(5, LocalDate.class));
					m.put("selfDone", rs.getObject(6) != null);
					m.put("managerDone", rs.getObject(7) != null);
					return m;
				}).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("reviews", reviews);
		out.put("goals", goalsOf(me));
		out.put("ratings", RATINGS);
		return out;
	}

	private List<Map<String, Object>> goalsOf(UUID emp) {
		return jdbc.sql("select id, title, detail, due_date, progress, status, updated_at from goal where employee_id = :e order by status, due_date nulls last, created_at")
				.param("e", emp).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("title", rs.getString(2));
					m.put("detail", rs.getString(3));
					m.put("dueDate", rs.getObject(4, LocalDate.class));
					m.put("progress", rs.getInt(5));
					m.put("status", rs.getString(6));
					m.put("updatedAt", rs.getObject(7, OffsetDateTime.class));
					return m;
				}).list();
	}

	/** Body: {@code employeeId (optional, a manager or HR adding for someone), title, detail, dueDate, progress, status}. */
	@Transactional
	public Map<String, Object> saveGoal(UUID id, Map<String, Object> b) {
		UUID emp;
		if (id == null) {
			emp = b.get("employeeId") == null ? currentUser.requireEmployeeId() : UUID.fromString(str(b.get("employeeId")));
		}
		else {
			emp = jdbc.sql("select employee_id from goal where id = :id and org_id = :org").param("id", id).param("org", org()).query(UUID.class).optional()
					.orElseThrow(() -> new NotFoundException("Goal not found"));
		}
		requireGoalAccess(emp);
		String title = str(b.get("title"));
		if (title == null || title.isBlank() || title.length() > 160) {
			throw problem("title", "Give the goal a title up to 160 characters");
		}
		int progress = b.get("progress") == null ? 0 : Math.max(0, Math.min(100, Integer.parseInt("0" + str(b.get("progress")).replaceAll("[^0-9]", ""))));
		String asked = str(b.get("status"));
		String status = asked != null && List.of("ACTIVE", "DONE", "DROPPED").contains(asked) ? asked : progress == 100 ? "DONE" : "ACTIVE";
		LocalDate due = b.get("dueDate") == null || str(b.get("dueDate")).isBlank() ? null : date(b.get("dueDate"), "dueDate");
		String detail = str(b.get("detail"));
		if (id == null) {
			id = jdbc.sql("""
					insert into goal (org_id, employee_id, title, detail, due_date, progress, status, created_by) values (:org, :e, :t, :d, :due, :p, :s, :by) returning id""")
					.param("org", org()).param("e", emp).param("t", title.strip()).param("d", detail, java.sql.Types.VARCHAR).param("due", due, java.sql.Types.DATE)
					.param("p", progress).param("s", status).param("by", me()).query(UUID.class).single();
		}
		else {
			jdbc.sql("update goal set title = :t, detail = :d, due_date = :due, progress = :p, status = :s, updated_at = now() where id = :id").param("t", title.strip())
					.param("d", detail, java.sql.Types.VARCHAR).param("due", due, java.sql.Types.DATE).param("p", progress).param("s", status).param("id", id).update();
		}
		return Map.of("id", id, "goals", goalsOf(emp));
	}

	@Transactional
	public void deleteGoal(UUID id) {
		UUID emp = jdbc.sql("select employee_id from goal where id = :id and org_id = :org").param("id", id).param("org", org()).query(UUID.class).optional()
				.orElseThrow(() -> new NotFoundException("Goal not found"));
		requireGoalAccess(emp);
		jdbc.sql("delete from goal where id = :id").param("id", id).update();
	}

	// ================================================================== helpers

	private void requireGoalAccess(UUID emp) {
		if (emp.equals(me()) || hr()) {
			return;
		}
		boolean manager = jdbc.sql("select count(*) from employee where id = :e and manager_id = :me").param("e", emp).param("me", me()).query(Long.class).single() > 0;
		if (!manager) {
			throw new ForbiddenException("Only the person, their manager or HR can change these goals");
		}
	}

	private void requireOpen(Map<String, Object> r, String submittedKey) {
		if (!"ACTIVE".equals(r.get("cycleStatus"))) {
			throw new ConflictException("This review cycle isn't open");
		}
		if (r.get(submittedKey) != null) {
			throw new ConflictException("Already submitted");
		}
	}

	private Map<String, String> answers(Object raw, List<String> keys, boolean submit) {
		Map<String, String> out = new LinkedHashMap<>();
		Map<?, ?> m = raw instanceof Map<?, ?> x ? x : Map.of();
		for (String k : keys) {
			String v = m.get(k) == null ? "" : m.get(k).toString().strip();
			if (v.length() > 5000) {
				throw problem(k, "Keep each answer under 5,000 characters");
			}
			out.put(k, v);
		}
		if (submit && out.values().stream().allMatch(String::isBlank)) {
			throw problem(keys.get(0), "Write something before submitting");
		}
		return out;
	}

	private Integer rating(Object v, boolean required) {
		if (v == null || str(v).isBlank()) {
			if (required) {
				throw problem("rating", "Pick a rating");
			}
			return null;
		}
		int r;
		try {
			r = Integer.parseInt(str(v));
		}
		catch (NumberFormatException e) {
			throw problem("rating", "Pick a rating");
		}
		if (r < 1 || r > 5) {
			throw problem("rating", "Pick a rating");
		}
		return r;
	}

	private Map<String, Object> reviewRow(UUID id) {
		return jdbc.sql("""
				select r.id, r.cycle_id, c.name, c.status, c.self_due, c.manager_due, c.period_start, c.period_end, r.employee_id, e.display_name, jt.name, d.name,
				       r.reviewer_id, m.display_name, r.self_answers::text, r.self_rating, r.self_submitted_at, r.manager_answers::text, r.manager_rating,
				       r.manager_submitted_at, r.final_rating, r.final_note
				from review r join review_cycle c on c.id = r.cycle_id join employee e on e.id = r.employee_id left join job_title jt on jt.id = e.title_id
				left join department d on d.id = e.department_id left join employee m on m.id = r.reviewer_id where r.id = :id and c.org_id = :org""").param("id", id)
				.param("org", org()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("cycleId", rs.getObject(2, UUID.class));
					m.put("cycleName", rs.getString(3));
					m.put("cycleStatus", rs.getString(4));
					m.put("selfDue", rs.getObject(5, LocalDate.class));
					m.put("managerDue", rs.getObject(6, LocalDate.class));
					m.put("periodStart", rs.getObject(7, LocalDate.class));
					m.put("periodEnd", rs.getObject(8, LocalDate.class));
					m.put("employeeId", rs.getObject(9, UUID.class));
					m.put("employeeName", rs.getString(10));
					m.put("employeeTitle", nz(rs.getString(11)));
					m.put("department", nz(rs.getString(12)));
					m.put("reviewerId", rs.getObject(13, UUID.class));
					m.put("reviewerName", rs.getString(14));
					m.put("selfAnswers", json.readValue(rs.getString(15), new TypeReference<Map<String, String>>() {
					}));
					m.put("selfRating", rs.getObject(16));
					m.put("selfSubmittedAt", rs.getObject(17, OffsetDateTime.class));
					m.put("managerAnswers", json.readValue(rs.getString(18), new TypeReference<Map<String, String>>() {
					}));
					m.put("managerRating", rs.getObject(19));
					m.put("managerSubmittedAt", rs.getObject(20, OffsetDateTime.class));
					m.put("finalRating", rs.getObject(21));
					m.put("finalNote", rs.getString(22));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Review not found"));
	}

	private Map<String, Object> cycleRow(UUID id) {
		return jdbc.sql("select id, name, period_start, period_end, self_due, manager_due, status from review_cycle where id = :id and org_id = :org").param("id", id)
				.param("org", org()).query((rs, i) -> cycleMap(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
						rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getString(7)))
				.optional().orElseThrow(() -> new NotFoundException("Review cycle not found"));
	}

	private static Map<String, Object> cycleMap(UUID id, String name, LocalDate ps, LocalDate pe, LocalDate sd, LocalDate md, String status) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("name", name);
		m.put("periodStart", ps);
		m.put("periodEnd", pe);
		m.put("selfDue", sd);
		m.put("managerDue", md);
		m.put("status", status);
		return m;
	}

	private boolean hr() {
		return access.has("performance", "EDIT") && access.has("employee", "VIEW") || access.has("performance", "ADMIN");
	}

	private void requireHr() {
		if (!hr()) {
			throw new ForbiddenException("Only HR can manage review cycles");
		}
	}

	private static LocalDate date(Object v, String field) {
		try {
			return LocalDate.parse(String.valueOf(v));
		}
		catch (RuntimeException e) {
			throw problem(field, "Pick a date");
		}
	}

	private static UnprocessableException problem(String field, String msg) {
		return new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", msg)));
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private UUID me() {
		return currentUser.employeeId();
	}

	private UUID org() {
		return currentUser.orgId();
	}
}
