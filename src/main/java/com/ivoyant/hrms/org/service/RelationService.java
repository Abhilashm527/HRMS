package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.RelationCreatedEvent;
import com.ivoyant.hrms.common.event.RelationEndedEvent;
import com.ivoyant.hrms.common.event.RelationStartedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.Relations;
import com.ivoyant.hrms.org.dto.RelationDtos;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dotted lines and mentors. They never carry authority: a dotted-line manager only gets to see the person's basic profile, and a
 * mentor gets nothing extra. A person has at most one dotted line and two mentors at any date, and a mentor at most ten
 * mentees (a setting). Relations end, they are not deleted (except one that never started).
 */
@Service
public class RelationService implements Relations {

	private static final Logger log = LoggerFactory.getLogger(RelationService.class);
	private static final String ENTITY = "Relation";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
	private static final int MAX_MENTORS = 2;

	private static final String STATUS = "case when r.ended_at is not null or (r.to_date is not null and r.to_date < current_date) then 'ENDED' "
			+ "when r.from_date > current_date then 'SCHEDULED' else 'ACTIVE' end";
	private static final String SELECT = "select r.id, r.type, r.employee_id, e.display_name, et.name, r.related_id, x.display_name, xt.name, "
			+ "r.from_date, r.to_date, " + STATUS + " as status, r.note, r.end_reason, r.version "
			+ "from employee_relation r join employee e on e.id = r.employee_id left join job_title et on et.id = e.title_id "
			+ "join employee x on x.id = r.related_id left join job_title xt on xt.id = x.title_id ";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final People people;
	private final OrgSettings settings;
	private final FgaOutbox fga;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public RelationService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, People people, OrgSettings settings,
			FgaOutbox fga, Audit audit, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.people = people;
		this.settings = settings;
		this.fga = fga;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reads

	private static RelationDtos.Node node(java.sql.ResultSet rs) throws java.sql.SQLException {
		String type = rs.getString(2);
		return new RelationDtos.Node(rs.getObject(1, UUID.class), type, "DOTTED".equals(type) ? "Dotted line" : "Mentor",
				new RelationDtos.Person(rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5)),
				new RelationDtos.Person(rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8)),
				rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class), rs.getString(11), rs.getString(12),
				rs.getString(13), rs.getLong(14), List.of());
	}

	@Transactional(readOnly = true)
	public PageResponse<RelationDtos.Node> list(String type, String status, UUID employeeId, UUID relatedId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where r.org_id = :org");
		if (type != null && !type.isBlank()) {
			where.append(" and r.type = :type");
		}
		if (status != null && !status.isBlank()) {
			where.append(" and ").append(STATUS).append(" = :status");
		}
		if (employeeId != null) {
			where.append(" and r.employee_id = :emp");
		}
		if (relatedId != null) {
			where.append(" and r.related_id = :rel");
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(e.display_name) like :q or lower(x.display_name) like :q or lower(coalesce(r.note, '')) like :q)");
		}
		int pageSize = Math.min(Math.max(size, 1), 200);
		var count = jdbc.sql("select count(*) from employee_relation r join employee e on e.id = r.employee_id join employee x on x.id = r.related_id"
				+ where).param("org", currentUser.orgId());
		var rows = jdbc.sql(SELECT + where + " order by r.from_date desc, e.display_name limit :limit offset :offset").param("org", currentUser.orgId())
				.param("limit", pageSize).param("offset", Math.max(page, 0) * pageSize);
		if (type != null && !type.isBlank()) {
			String t = parseType(type);
			count = count.param("type", t);
			rows = rows.param("type", t);
		}
		if (status != null && !status.isBlank()) {
			count = count.param("status", status.trim().toUpperCase(Locale.ROOT));
			rows = rows.param("status", status.trim().toUpperCase(Locale.ROOT));
		}
		if (employeeId != null) {
			count = count.param("emp", employeeId);
			rows = rows.param("emp", employeeId);
		}
		if (relatedId != null) {
			count = count.param("rel", relatedId);
			rows = rows.param("rel", relatedId);
		}
		if (q != null && !q.isBlank()) {
			String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
			count = count.param("q", like);
			rows = rows.param("q", like);
		}
		long total = count.query(Long.class).single();
		return new PageResponse<>(rows.query((rs, i) -> node(rs)).list(), Math.max(page, 0), pageSize, total);
	}

	@Transactional(readOnly = true)
	public RelationDtos.Summary summary() {
		UUID org = currentUser.orgId();
		long direct = jdbc.sql("select count(*) from employee where org_id = :org and status <> 'EXITED' and manager_id is not null")
				.param("org", org).query(Long.class).single();
		return new RelationDtos.Summary(direct, countStatus("DOTTED", "ACTIVE"), countStatus("MENTOR", "ACTIVE"), countStatus(null, "ENDED"));
	}

	private long countStatus(String type, String status) {
		return jdbc.sql("select count(*) from employee_relation r where r.org_id = :org and " + STATUS + " = :status"
				+ (type == null ? "" : " and r.type = :type")).param("org", currentUser.orgId()).param("status", status)
				.param("type", type, java.sql.Types.VARCHAR).query(Long.class).single();
	}

	@Transactional(readOnly = true)
	public RelationDtos.EmployeeRelations ofEmployee(UUID employee) {
		require(employee);
		List<RelationDtos.Node> mine = jdbc.sql(SELECT + " where r.employee_id = :e and " + STATUS + " <> 'ENDED' order by r.from_date")
				.param("e", employee).query((rs, i) -> node(rs)).list();
		List<RelationDtos.Node> others = jdbc.sql(SELECT + " where r.related_id = :e and " + STATUS + " <> 'ENDED' order by r.from_date")
				.param("e", employee).query((rs, i) -> node(rs)).list();
		return new RelationDtos.EmployeeRelations(mine.stream().filter(n -> "DOTTED".equals(n.type())).toList(),
				mine.stream().filter(n -> "MENTOR".equals(n.type())).toList(),
				others.stream().filter(n -> "MENTOR".equals(n.type())).toList(),
				others.stream().filter(n -> "DOTTED".equals(n.type())).toList());
	}

	@Transactional(readOnly = true)
	public RelationDtos.Node get(UUID id) {
		return jdbc.sql(SELECT + " where r.id = :id and r.org_id = :org").param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> node(rs)).optional().orElseThrow(() -> new NotFoundException("Relation " + id + " not found"));
	}

	// ---- the facade ----

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> dottedOf(UUID employeeId) {
		return jdbc.sql("select related_id from employee_relation r where r.employee_id = :e and r.type = 'DOTTED' and " + STATUS + " = 'ACTIVE' limit 1")
				.param("e", employeeId).query(UUID.class).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> mentorsOf(UUID employeeId) {
		return jdbc.sql("select related_id from employee_relation r where r.employee_id = :e and r.type = 'MENTOR' and " + STATUS + " = 'ACTIVE'")
				.param("e", employeeId).query(UUID.class).list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> currentDottedRelation(UUID employeeId) {
		return jdbc.sql("select id from employee_relation where employee_id = :e and type = 'DOTTED' and ended_at is null "
				+ "and (to_date is null or to_date >= current_date) order by from_date desc limit 1").param("e", employeeId).query(UUID.class).optional();
	}

	@Override
	@Transactional
	public void addDotted(UUID employeeId, UUID relatedId, LocalDate from) {
		create(Map.of("employeeId", employeeId.toString(), "relatedId", relatedId.toString(), "type", "DOTTED", "fromDate", from.toString()));
	}

	@Override
	@Transactional
	public void endRelation(UUID relationId, String reason) {
		end(relationId, Map.of("reason", reason));
	}

	// ================================================================== writes

	/** Body: {@code employeeId, relatedId, type (DOTTED or MENTOR), fromDate, toDate, note}. */
	@Transactional
	public RelationDtos.Node create(Map<String, Object> body) {
		UUID employee = uuid(body.get("employeeId"), "employeeId");
		UUID related = uuid(body.get("relatedId"), "relatedId");
		String type = parseType(body.get("type") == null ? null : body.get("type").toString());
		LocalDate from = date(body.get("fromDate"), LocalDate.now(), "fromDate");
		LocalDate to = body.get("toDate") == null || body.get("toDate").toString().isBlank() ? null : date(body.get("toDate"), null, "toDate");
		String note = body.get("note") == null || body.get("note").toString().isBlank() ? null : body.get("note").toString().trim();
		if (note != null && note.length() > 500) {
			throw invalid("note", "INVALID", "The note can be at most 500 characters");
		}
		List<String> warnings = new ArrayList<>();
		validate(null, employee, related, type, from, to, warnings);
		UUID id = jdbc.sql("""
				insert into employee_relation (org_id, employee_id, related_id, type, from_date, to_date, note, created_by, updated_by)
				values (:org, :e, :r, :t, :f, :to, :n, :by, :by) returning id
				""").param("org", currentUser.orgId()).param("e", employee).param("r", related).param("t", type).param("f", from)
				.param("to", to, java.sql.Types.DATE).param("n", note, java.sql.Types.VARCHAR)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		RelationDtos.Node out = get(id).withWarnings(warnings);
		audit.created("org", ENTITY, id, out);
		events.publishEvent(new RelationCreatedEvent(id, type, employee, related));
		if ("DOTTED".equals(type) && !from.isAfter(LocalDate.now())) {
			fga.write(FgaTuples.dotted(employee, related));
		}
		if (!from.isAfter(LocalDate.now())) {
			events.publishEvent(new RelationStartedEvent(id, type, employee, related));
		}
		return out;
	}

	/** Only dates and the note change; to change a person or the type, end the relation and add a new one. */
	@Transactional
	public RelationDtos.Node update(UUID id, Map<String, Object> patch, String ifMatch) {
		RelationDtos.Node cur = get(id);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(cur.version()))) {
			throw new StaleException("Someone else changed this relation. Reload and try again.");
		}
		if ("ENDED".equals(cur.status())) {
			throw new ConflictException("This relation has ended. Add a new one instead");
		}
		LocalDate from = patch.containsKey("fromDate") ? date(patch.get("fromDate"), LocalDate.now(), "fromDate") : cur.fromDate();
		LocalDate to = patch.containsKey("toDate") ? (patch.get("toDate") == null || patch.get("toDate").toString().isBlank() ? null
				: date(patch.get("toDate"), null, "toDate")) : cur.toDate();
		String note = patch.containsKey("note") ? (patch.get("note") == null || patch.get("note").toString().isBlank() ? null
				: patch.get("note").toString().trim()) : cur.note();
		List<String> warnings = new ArrayList<>();
		validate(id, cur.employee().id(), cur.related().id(), cur.type(), from, to, warnings);
		jdbc.sql("update employee_relation set from_date = :f, to_date = :to, note = :n, updated_at = now(), updated_by = :by, version = version + 1 where id = :id")
				.param("f", from).param("to", to, java.sql.Types.DATE).param("n", note, java.sql.Types.VARCHAR)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		RelationDtos.Node after = get(id).withWarnings(warnings);
		audit.updated("org", ENTITY, id, cur, after);
		return after;
	}

	/** Ends a relation today (or on {@code endDate}); it stays in the history. */
	@Transactional
	public RelationDtos.Node end(UUID id, Map<String, Object> body) {
		RelationDtos.Node cur = get(id);
		requireManage(cur.type(), cur.employee().id());
		if ("ENDED".equals(cur.status())) {
			throw new ConflictException("This relation has already ended");
		}
		LocalDate on = date(body == null ? null : body.get("endDate"), LocalDate.now(), "endDate");
		String reason = body == null || body.get("reason") == null || body.get("reason").toString().isBlank() ? "MANUAL"
				: body.get("reason").toString().trim();
		endRow(id, cur.type(), cur.employee().id(), cur.related().id(), on.isBefore(cur.fromDate()) ? cur.fromDate() : on, reason);
		return get(id);
	}

	/** Only a relation that never started can be deleted; anything else is ended. */
	@Transactional
	public void delete(UUID id) {
		RelationDtos.Node cur = get(id);
		requireManage(cur.type(), cur.employee().id());
		if (!"SCHEDULED".equals(cur.status())) {
			throw new ConflictException("This relation has started. End it instead of deleting it");
		}
		jdbc.sql("delete from employee_relation where id = :id").param("id", id).update();
		audit.deleted("org", ENTITY, id, cur);
	}

	private void endRow(UUID id, String type, UUID employee, UUID related, LocalDate on, String reason) {
		jdbc.sql("update employee_relation set to_date = case when to_date is null or to_date > :on then :on else to_date end, "
				+ "ended_at = now(), ended_by = :by, end_reason = :reason, updated_at = now(), version = version + 1 where id = :id")
				.param("on", on).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("reason", reason).param("id", id).update();
		if ("DOTTED".equals(type)) {
			fga.delete(FgaTuples.dotted(employee, related));
		}
		audit.publish("org", ENTITY, id, "END", null, Map.of("reason", reason));
		events.publishEvent(new RelationEndedEvent(id, type, employee, related, reason));
	}

	/** A person left: every relation they are in ends on their exit date. */
	void endForEmployee(UUID employee, LocalDate on) {
		for (Object[] r : jdbc.sql("select id, type, employee_id, related_id from employee_relation where ended_at is null and (employee_id = :e or related_id = :e)")
				.param("e", employee).query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
						rs.getObject(4, UUID.class)}).list()) {
			endRow((UUID) r[0], (String) r[1], (UUID) r[2], (UUID) r[3], on, "EXITED");
		}
	}

	/** A manager change made the dotted-line manager the direct manager: the dotted line is no longer needed. */
	void endBecameDirect(UUID employee, UUID newManager) {
		for (Object[] r : jdbc.sql("select id, related_id from employee_relation where employee_id = :e and related_id = :m and type = 'DOTTED' and ended_at is null")
				.param("e", employee).param("m", newManager).query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list()) {
			endRow((UUID) r[0], "DOTTED", employee, (UUID) r[1], LocalDate.now(), "BECAME_DIRECT");
		}
	}

	/** Every night: relations past their end date are ended, and ones starting today are announced. */
	@Scheduled(cron = "0 10 0 * * *")
	@Transactional
	public void nightly() {
		int ended = 0;
		for (Object[] r : jdbc.sql("select id, type, employee_id, related_id, to_date from employee_relation where ended_at is null and to_date < current_date")
				.query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
						rs.getObject(5, LocalDate.class)}).list()) {
			endRow((UUID) r[0], (String) r[1], (UUID) r[2], (UUID) r[3], (LocalDate) r[4], "EXPIRED");
			ended++;
		}
		for (Object[] r : jdbc.sql("select id, type, employee_id, related_id from employee_relation where ended_at is null and from_date = current_date")
				.query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)}).list()) {
			if ("DOTTED".equals(r[1])) {
				fga.write(FgaTuples.dotted((UUID) r[2], (UUID) r[3]));
			}
			events.publishEvent(new RelationStartedEvent((UUID) r[0], (String) r[1], (UUID) r[2], (UUID) r[3]));
		}
		if (ended > 0) {
			log.info("{} relations reached their end date", ended);
		}
	}

	// ================================================================== rules

	private void requireManage(String type, UUID employee) {
		UUID me = currentUser.employeeId();
		boolean manager = "MENTOR".equals(type) && me != null && me.equals(jdbc.sql("select manager_id from employee where id = :e").param("e", employee)
				.query(UUID.class).optional().orElse(null));
		if (!manager && !access.has("org", "EDIT")) {
			throw new ForbiddenException("You don't have permission to change reporting lines");
		}
	}

	private void validate(UUID selfId, UUID employee, UUID related, String type, LocalDate from, LocalDate to, List<String> warnings) {
		requireManage(type, employee);
		if (employee.equals(related)) {
			throw invalid("relatedId", "SAME_PERSON", "A person can't be related to themselves");
		}
		if (to != null && to.isBefore(from)) {
			throw invalid("toDate", "DATES_ORDER", "The end date must not be before the start date");
		}
		if (from.isBefore(LocalDate.now()) && !access.has("org", "EDIT")) {
			throw new ForbiddenException("Only HR can add a relation that started in the past");
		}
		People.Emp e = people.find(employee);
		People.Emp r = people.find(related);
		if (e == null || !"ACTIVE".equals(e.status()) && !"ON_NOTICE".equals(e.status())) {
			throw invalid("employeeId", "INVALID", "Only current employees can have a dotted line or a mentor");
		}
		if (r == null || !r.live()) {
			throw invalid("relatedId", "INVALID", "The " + ("DOTTED".equals(type) ? "dotted-line manager" : "mentor") + " must be a current employee");
		}
		if ("ON_NOTICE".equals(r.status())) {
			warnings.add(r.name() + " is serving notice");
		}
		UUID manager = jdbc.sql("select manager_id from employee where id = :e").param("e", employee).query(UUID.class).optional().orElse(null);
		if ("DOTTED".equals(type)) {
			if (related.equals(manager)) {
				throw new ConflictException(r.name() + " is already the direct manager");
			}
			boolean below = jdbc.sql("""
					with recursive up as (
					    select id, manager_id, 0 as d from employee where id = :r
					    union all
					    select e.id, e.manager_id, up.d + 1 from employee e join up on e.id = up.manager_id where up.d < 200)
					select count(*) from up where id = :e""").param("r", related).param("e", employee).query(Long.class).single() > 0;
			if (below) {
				throw new ConflictException(r.name() + " reports to " + e.name() + "; a dotted line can't point down");
			}
			Object[] other = jdbc.sql("select r2.related_id, r2.to_date, x.display_name from employee_relation r2 join employee x on x.id = r2.related_id "
					+ "where r2.employee_id = :e and r2.type = 'DOTTED' and r2.ended_at is null and (cast(:self as uuid) is null or r2.id <> cast(:self as uuid)) "
					+ "and r2.from_date <= coalesce(cast(:to as date), date '9999-12-31') and coalesce(r2.to_date, date '9999-12-31') >= cast(:from as date) limit 1")
					.param("e", employee).param("self", selfId, java.sql.Types.OTHER).param("to", to, java.sql.Types.DATE).param("from", from)
					.query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getString(3)}).optional().orElse(null);
			if (other != null) {
				throw new CodedConflictException("DOTTED_EXISTS", e.name() + " already has a dotted line to " + other[2]
						+ (other[1] == null ? "" : " until " + DAY.format((LocalDate) other[1])) + ". End it first.");
			}
			return;
		}
		if (related.equals(manager)) {
			throw invalid("relatedId", "MENTOR_IS_MANAGER", "A mentor can't be the direct manager: mentors are outside the line");
		}
		if (count("select count(*) from employee_relation where employee_id = :e and type = 'MENTOR' and ended_at is null and (cast(:self as uuid) is null or id <> cast(:self as uuid)) "
				+ "and from_date <= coalesce(cast(:to as date), date '9999-12-31') and coalesce(to_date, date '9999-12-31') >= cast(:from as date)", employee, selfId, from, to) >= MAX_MENTORS) {
			throw new ConflictException(e.name() + " already has " + MAX_MENTORS + " mentors");
		}
		if (count("select count(*) from employee_relation where employee_id = :e and related_id = :r and type = 'MENTOR' and ended_at is null and (cast(:self as uuid) is null or id <> cast(:self as uuid)) "
				+ "and from_date <= coalesce(cast(:to as date), date '9999-12-31') and coalesce(to_date, date '9999-12-31') >= cast(:from as date)", employee, selfId, from, to, related) > 0) {
			throw new ConflictException(r.name() + " is already mentoring " + e.name() + " in that period");
		}
		long mentees = jdbc.sql("select count(*) from employee_relation where related_id = :r and type = 'MENTOR' and ended_at is null and (cast(:self as uuid) is null or id <> cast(:self as uuid)) "
				+ "and from_date <= coalesce(cast(:to as date), date '9999-12-31') and coalesce(to_date, date '9999-12-31') >= cast(:from as date)")
				.param("r", related).param("self", selfId, java.sql.Types.OTHER).param("to", to, java.sql.Types.DATE).param("from", from)
				.query(Long.class).single();
		if (mentees >= settings.maxMentees()) {
			throw new ConflictException(r.name() + " already mentors " + mentees + " people (the limit is " + settings.maxMentees() + ")");
		}
	}

	private long count(String sql, UUID employee, UUID self, LocalDate from, LocalDate to, UUID... related) {
		var stmt = jdbc.sql(sql).param("e", employee).param("self", self, java.sql.Types.OTHER).param("to", to, java.sql.Types.DATE).param("from", from);
		if (related.length > 0) {
			stmt = stmt.param("r", related[0]);
		}
		return stmt.query(Long.class).single();
	}

	private static String parseType(String text) {
		String t = text == null ? "" : text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		if (t.equals("DOTTED") || t.equals("DOTTED_LINE")) {
			return "DOTTED";
		}
		if (t.equals("MENTOR")) {
			return "MENTOR";
		}
		throw invalid("type", "INVALID", "Type must be Dotted line or Mentor");
	}

	private People.Emp require(UUID employee) {
		People.Emp e = people.find(employee);
		if (e == null) {
			throw new NotFoundException("Employee " + employee + " not found");
		}
		return e;
	}

	private static UUID uuid(Object v, String field) {
		try {
			return UUID.fromString(String.valueOf(v));
		}
		catch (IllegalArgumentException ex) {
			throw invalid(field, "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private static LocalDate date(Object v, LocalDate fallback, String field) {
		if (v == null || v.toString().isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(v.toString().trim());
		}
		catch (java.time.format.DateTimeParseException ex) {
			throw invalid(field, "INVALID", "'" + v + "' is not a date (use yyyy-mm-dd)");
		}
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
