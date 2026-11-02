package com.ivoyant.hrms.recruiting.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recruiting. Jobs collect candidates who move Applied → Screening → Interview → Offer → Hired, or Rejected at any point. Interviews
 * have a panel; each panelist leaves feedback (they need no recruiting access for that). An accepted offer moves the candidate to
 * Hired; onboarding is then started from Onboarding with their details filled in. Needs Recruiting access: View to look, Edit to change.
 */
@Service
public class RecruitingService {

	public static final List<String> STAGES = List.of("APPLIED", "SCREENING", "INTERVIEW", "OFFER", "HIRED");
	public static final List<String> SOURCES = List.of("Careers page", "Referral", "LinkedIn", "Naukri", "Agency", "Campus", "Other");
	private static final Set<String> RECS = Set.of("STRONG_YES", "YES", "NO", "STRONG_NO");
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Notifier notifier;
	private final Audit audit;
	private final FileStore store;

	RecruitingService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Notifier notifier, Audit audit, FileStore store) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.notifier = notifier;
		this.audit = audit;
		this.store = store;
	}

	// ================================================================== overview

	@Transactional(readOnly = true)
	public Map<String, Object> overview() {
		require("VIEW");
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Long> byStage = new LinkedHashMap<>();
		STAGES.forEach(s -> byStage.put(s, 0L));
		byStage.put("REJECTED", 0L);
		jdbc.sql("""
				select c.stage, count(*) from candidate c join job_opening j on j.id = c.job_id where j.org_id = :org and c.created_at > now() - interval '120 days'
				group by c.stage""").param("org", org()).query((rs, i) -> byStage.put(rs.getString(1), rs.getLong(2))).list();
		// a funnel counts everyone who reached a stage or went past it
		List<Map<String, Object>> funnel = new ArrayList<>();
		long total = byStage.values().stream().mapToLong(Long::longValue).sum();
		for (int i = 0; i < STAGES.size(); i++) {
			long reached = 0;
			for (int k = i; k < STAGES.size(); k++) {
				reached += byStage.get(STAGES.get(k));
			}
			if (i == 0) {
				reached = total;
			}
			funnel.add(Map.of("stage", STAGES.get(i), "n", reached));
		}
		out.put("funnel", funnel);
		out.put("kpis", Map.of("openJobs", count("select count(*) from job_opening where org_id = :org and status = 'OPEN'"), "openings",
				count("select coalesce(sum(openings), 0) from job_opening where org_id = :org and status = 'OPEN'"), "active",
				count("select count(*) from candidate c join job_opening j on j.id = c.job_id where j.org_id = :org and c.stage in ('APPLIED','SCREENING','INTERVIEW','OFFER')"),
				"interviewsWeek",
				count("select count(*) from interview i join candidate c on c.id = i.candidate_id join job_opening j on j.id = c.job_id where j.org_id = :org and i.status = 'SCHEDULED' and i.scheduled_at between now() and now() + interval '7 days'"),
				"offersOut", count("select count(*) from job_offer o join candidate c on c.id = o.candidate_id join job_opening j on j.id = c.job_id where j.org_id = :org and o.status = 'SENT'"),
				"hired90",
				count("select count(*) from candidate c join job_opening j on j.id = c.job_id where j.org_id = :org and c.stage = 'HIRED' and c.stage_changed_at > now() - interval '90 days'")));
		out.put("sources", jdbc.sql("""
				select c.source, count(*), count(*) filter (where c.stage in ('INTERVIEW','OFFER','HIRED')) from candidate c join job_opening j on j.id = c.job_id
				where j.org_id = :org group by c.source order by count(*) desc""").param("org", org())
				.query((rs, i) -> Map.of("source", rs.getString(1), "n", rs.getLong(2), "interviewed", rs.getLong(3))).list());
		List<Map<String, Object>> todo = new ArrayList<>();
		jdbc.sql("""
				select c.id, c.name, j.title, c.created_at from candidate c join job_opening j on j.id = c.job_id
				where j.org_id = :org and c.stage = 'APPLIED' and c.created_at < now() - interval '3 days' order by c.created_at limit 5""").param("org", org())
				.query((rs, i) -> todo.add(Map.of("candidateId", rs.getObject(1, UUID.class), "text", rs.getString(2) + " is waiting for screening", "sub", rs.getString(3), "action", "Screen")))
				.list();
		jdbc.sql("""
				select c.id, c.name, i.round from interview i join candidate c on c.id = i.candidate_id join job_opening j on j.id = c.job_id
				where j.org_id = :org and i.status = 'SCHEDULED' and i.scheduled_at < now() order by i.scheduled_at limit 5""").param("org", org())
				.query((rs, i) -> todo.add(Map.of("candidateId", rs.getObject(1, UUID.class), "text", rs.getString(2) + " · " + rs.getString(3) + " has happened", "sub",
						"Mark it done and collect feedback", "action", "Open")))
				.list();
		jdbc.sql("""
				select c.id, c.name, o.created_at from job_offer o join candidate c on c.id = o.candidate_id join job_opening j on j.id = c.job_id
				where j.org_id = :org and o.status = 'SENT' and o.created_at < now() - interval '5 days' limit 5""").param("org", org())
				.query((rs, i) -> todo.add(Map.of("candidateId", rs.getObject(1, UUID.class), "text", "Offer to " + rs.getString(2) + " has no answer yet", "sub",
						"Sent " + rs.getObject(3, OffsetDateTime.class).toLocalDate(), "action", "Follow up")))
				.list();
		out.put("todo", todo);
		out.put("upcoming", interviews(true, null).stream().limit(6).toList());
		out.put("canEdit", access.has("recruiting", "EDIT"));
		return out;
	}

	private long count(String sql) {
		return jdbc.sql(sql).param("org", org()).query(Long.class).single();
	}

	// ================================================================== jobs

	@Transactional(readOnly = true)
	public Map<String, Object> jobs() {
		require("VIEW");
		List<Map<String, Object>> rows = jdbc.sql("""
				select j.id, j.no, j.title, d.name, l.name, j.openings, j.experience, j.budget_min, j.budget_max, hm.display_name, rc.display_name, j.target_date, j.status,
				       j.created_at, j.department_id, j.location_id, j.hiring_manager_id, j.recruiter_id, j.description, j.skills,
				       count(c.id) filter (where c.stage = 'APPLIED'), count(c.id) filter (where c.stage = 'SCREENING'), count(c.id) filter (where c.stage = 'INTERVIEW'),
				       count(c.id) filter (where c.stage = 'OFFER'), count(c.id) filter (where c.stage = 'HIRED'), count(c.id) filter (where c.stage = 'REJECTED')
				from job_opening j left join department d on d.id = j.department_id left join location l on l.id = j.location_id
				left join employee hm on hm.id = j.hiring_manager_id left join employee rc on rc.id = j.recruiter_id left join candidate c on c.job_id = j.id
				where j.org_id = :org group by j.id, d.name, l.name, hm.display_name, rc.display_name order by (j.status = 'OPEN') desc, j.created_at desc""")
				.param("org", org()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("code", "JOB-" + String.format("%03d", rs.getLong(2)));
					m.put("title", rs.getString(3));
					m.put("department", rs.getString(4));
					m.put("location", rs.getString(5));
					m.put("openings", rs.getInt(6));
					m.put("experience", rs.getString(7));
					m.put("budgetMin", rs.getBigDecimal(8));
					m.put("budgetMax", rs.getBigDecimal(9));
					m.put("hiringManager", rs.getString(10));
					m.put("recruiter", rs.getString(11));
					m.put("targetDate", rs.getObject(12, LocalDate.class));
					m.put("status", rs.getString(13));
					m.put("createdAt", rs.getObject(14, OffsetDateTime.class));
					m.put("departmentId", rs.getObject(15, UUID.class));
					m.put("locationId", rs.getObject(16, UUID.class));
					m.put("hiringManagerId", rs.getObject(17, UUID.class));
					m.put("recruiterId", rs.getObject(18, UUID.class));
					m.put("description", rs.getString(19));
					m.put("skills", rs.getString(20));
					Map<String, Long> s = new LinkedHashMap<>();
					String[] k = {"APPLIED", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED"};
					for (int x = 0; x < k.length; x++) {
						s.put(k[x], rs.getLong(21 + x));
					}
					m.put("stages", s);
					return m;
				}).list();
		return Map.of("items", rows, "canEdit", access.has("recruiting", "EDIT"));
	}

	@Transactional
	public Map<String, Object> saveJob(UUID id, Map<String, Object> b) {
		require("EDIT");
		String title = str(b.get("title"));
		if (title == null || title.isBlank()) {
			throw problem("title", "Give the job a title");
		}
		String status = in(Set.of("DRAFT", "OPEN", "ON_HOLD", "CLOSED"), str(b.get("status"))) ? str(b.get("status")) : "OPEN";
		BigDecimal min = dec(b.get("budgetMin"));
		BigDecimal max = dec(b.get("budgetMax"));
		if (min != null && max != null && max.compareTo(min) < 0) {
			throw problem("budgetMax", "The top of the budget is below the bottom");
		}
		int openings = b.get("openings") == null ? 1 : Math.max(1, Math.min(500, Integer.parseInt("0" + str(b.get("openings")).replaceAll("[^0-9]", ""))));
		var q = id == null
				? jdbc.sql("""
						insert into job_opening (org_id, title, department_id, location_id, openings, experience, budget_min, budget_max, hiring_manager_id, recruiter_id,
						  target_date, description, skills, status, created_by)
						values (:org, :t, :d, :l, :o, :x, :bmin, :bmax, :hm, :rc, :tg, :desc, :sk, :st, :by) returning id""")
				: jdbc.sql("""
						update job_opening set title = :t, department_id = :d, location_id = :l, openings = :o, experience = :x, budget_min = :bmin, budget_max = :bmax,
						  hiring_manager_id = :hm, recruiter_id = :rc, target_date = :tg, description = :desc, skills = :sk, status = :st,
						  closed_at = case when :st = 'CLOSED' then coalesce(closed_at, now()) else null end
						where id = :id and org_id = :org returning id""").param("id", id);
		UUID out = q.param("org", org()).param("t", title.strip()).param("d", uuid(b.get("departmentId")), java.sql.Types.OTHER)
				.param("l", uuid(b.get("locationId")), java.sql.Types.OTHER).param("o", openings).param("x", str(b.get("experience")), java.sql.Types.VARCHAR)
				.param("bmin", min, java.sql.Types.NUMERIC).param("bmax", max, java.sql.Types.NUMERIC).param("hm", uuid(b.get("hiringManagerId")), java.sql.Types.OTHER)
				.param("rc", uuid(b.get("recruiterId")), java.sql.Types.OTHER).param("tg", date(b.get("targetDate")), java.sql.Types.DATE)
				.param("desc", str(b.get("description")), java.sql.Types.VARCHAR).param("sk", str(b.get("skills")), java.sql.Types.VARCHAR).param("st", status)
				.param("by", me(), java.sql.Types.OTHER).query(UUID.class).optional().orElseThrow(() -> new NotFoundException("Job not found"));
		audit.publish("recruiting", "Job", out, id == null ? "CREATE" : "UPDATE", null, Map.of("title", title, "status", status));
		return Map.of("id", out);
	}

	// ================================================================== candidates

	@Transactional(readOnly = true)
	public Map<String, Object> pipeline(UUID jobId) {
		require("VIEW");
		List<Map<String, Object>> rows = jdbc.sql("""
				select c.id, c.name, c.stage, c.source, c.current_company, c.current_title, c.experience_years, c.expected_ctc, c.notice_days, c.stage_changed_at, j.title, j.id,
				       (select max(i.scheduled_at) from interview i where i.candidate_id = c.id and i.status = 'SCHEDULED' and i.scheduled_at > now()),
				       (select round(avg(f.rating), 1) from interview_feedback f join interview i on i.id = f.interview_id where i.candidate_id = c.id)
				from candidate c join job_opening j on j.id = c.job_id where j.org_id = :org and (cast(:j as uuid) is null or j.id = :j)
				order by c.stage_changed_at desc""").param("org", org()).param("j", jobId, java.sql.Types.OTHER).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("stage", rs.getString(3));
			m.put("source", rs.getString(4));
			m.put("company", rs.getString(5));
			m.put("title", rs.getString(6));
			m.put("experience", rs.getBigDecimal(7));
			m.put("expectedCtc", rs.getBigDecimal(8));
			m.put("noticeDays", rs.getObject(9));
			m.put("since", rs.getObject(10, OffsetDateTime.class));
			m.put("job", Map.of("title", rs.getString(11), "id", rs.getObject(12, UUID.class)));
			m.put("nextInterview", rs.getObject(13, OffsetDateTime.class));
			m.put("score", rs.getBigDecimal(14));
			return m;
		}).list();
		return Map.of("items", rows, "stages", STAGES, "canEdit", access.has("recruiting", "EDIT"));
	}

	@Transactional
	public Map<String, Object> addCandidate(Map<String, Object> b, String fileName, byte[] resume) {
		require("EDIT");
		UUID job = uuid(b.get("jobId"));
		if (job == null || jdbc.sql("select count(*) from job_opening where id = :j and org_id = :org").param("j", job).param("org", org()).query(Long.class).single() == 0) {
			throw problem("jobId", "Pick the job");
		}
		String name = str(b.get("name"));
		if (name == null || name.isBlank()) {
			throw problem("name", "Enter the candidate's name");
		}
		String email = str(b.get("email"));
		if (email != null && !email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
			throw problem("email", "Enter a valid email");
		}
		if (email != null && !email.isBlank() && jdbc.sql("select count(*) from candidate where job_id = :j and lower(email) = lower(:e)").param("j", job).param("e", email.strip())
				.query(Long.class).single() > 0) {
			throw problem("email", "This person has already applied for this job");
		}
		String source = in(SOURCES, str(b.get("source"))) ? str(b.get("source")) : "Other";
		UUID id = jdbc.sql("""
				insert into candidate (org_id, job_id, name, email, phone, source, referred_by, current_company, current_title, experience_years, notice_days, expected_ctc,
				  notes, created_by) values (:org, :j, :n, :e, :p, :s, :ref, :co, :ti, :x, :nd, :ctc, :notes, :by) returning id""").param("org", org()).param("j", job)
				.param("n", name.strip()).param("e", blankNull(email), java.sql.Types.VARCHAR).param("p", blankNull(str(b.get("phone"))), java.sql.Types.VARCHAR).param("s", source)
				.param("ref", uuid(b.get("referredBy")), java.sql.Types.OTHER).param("co", blankNull(str(b.get("company"))), java.sql.Types.VARCHAR)
				.param("ti", blankNull(str(b.get("title"))), java.sql.Types.VARCHAR).param("x", dec(b.get("experience")), java.sql.Types.NUMERIC)
				.param("nd", b.get("noticeDays") == null || str(b.get("noticeDays")).isBlank() ? null : Integer.parseInt(str(b.get("noticeDays"))), java.sql.Types.INTEGER)
				.param("ctc", dec(b.get("expectedCtc")), java.sql.Types.NUMERIC).param("notes", blankNull(str(b.get("notes"))), java.sql.Types.VARCHAR)
				.param("by", me(), java.sql.Types.OTHER).query(UUID.class).single();
		if (resume != null && resume.length > 0) {
			saveResume(id, fileName, resume);
		}
		event(id, "Added by " + myName() + " · source " + source);
		audit.publish("recruiting", "Candidate", id, "CREATE", null, Map.of("name", name));
		return candidate(id);
	}

	@Transactional
	public Map<String, Object> uploadResume(UUID id, String fileName, byte[] bytes) {
		require("EDIT");
		candidateRow(id);
		saveResume(id, fileName, bytes);
		event(id, "Résumé uploaded");
		return candidate(id);
	}

	private void saveResume(UUID id, String fileName, byte[] b) {
		if (b.length > 6L * 1024 * 1024) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Résumés can be up to 6 MB");
		}
		String n = fileName == null ? "resume" : fileName.replaceAll("[\\\\/\\r\\n]", "_");
		String lower = n.toLowerCase(Locale.ROOT);
		boolean pdf = b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
		boolean docx = b.length > 4 && b[0] == 'P' && b[1] == 'K' && lower.endsWith(".docx");
		boolean doc = b.length > 4 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && lower.endsWith(".doc");
		if (!pdf && !docx && !doc) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Upload the résumé as PDF or Word");
		}
		String key = org() + "/recruiting/" + id + "/" + UUID.randomUUID();
		try {
			store.put(key, b);
		}
		catch (IOException e) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved");
		}
		jdbc.sql("update candidate set resume_key = :k, resume_name = :n where id = :id").param("k", key).param("n", n).param("id", id).update();
	}

	@Transactional(readOnly = true)
	public FileContent resume(UUID id) {
		Map<String, Object> c = candidateRow(id);
		boolean panel = jdbc.sql("select count(*) from interview where candidate_id = :c and :me = any(panel)").param("c", id).param("me", me()).query(Long.class).single() > 0;
		if (!access.has("recruiting", "VIEW") && !panel) {
			throw new ForbiddenException("You can't see this candidate");
		}
		if (c.get("resumeKey") == null) {
			throw new NotFoundException("No résumé uploaded");
		}
		try {
			String n = (String) c.get("resumeName");
			String mime = n.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
			return new FileContent(store.get((String) c.get("resumeKey")), mime, n);
		}
		catch (IOException e) {
			throw new NotFoundException("The résumé is no longer in storage");
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Object> candidate(UUID id) {
		Map<String, Object> c = candidateRow(id);
		boolean panel = jdbc.sql("select count(*) from interview where candidate_id = :c and :me = any(panel)").param("c", id).param("me", me()).query(Long.class).single() > 0;
		if (!access.has("recruiting", "VIEW") && !panel) {
			throw new ForbiddenException("You can't see this candidate");
		}
		c.remove("resumeKey");
		c.put("interviews", interviews(false, id));
		c.put("offers", jdbc.sql("select id, ctc, joining_date, note, status, created_at, decided_at from job_offer where candidate_id = :c order by created_at desc")
				.param("c", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("ctc", rs.getBigDecimal(2));
					m.put("joiningDate", rs.getObject(3, LocalDate.class));
					m.put("note", rs.getString(4));
					m.put("status", rs.getString(5));
					m.put("createdAt", rs.getObject(6, OffsetDateTime.class));
					m.put("decidedAt", rs.getObject(7, OffsetDateTime.class));
					return m;
				}).list());
		c.put("events", jdbc.sql("select e.text, e.at, x.display_name from candidate_event e left join employee x on x.id = e.actor_id where e.candidate_id = :c order by e.at desc")
				.param("c", id).query((rs, i) -> Map.of("text", rs.getString(1), "at", rs.getObject(2, OffsetDateTime.class), "by", rs.getString(3) == null ? "" : rs.getString(3)))
				.list());
		c.put("canEdit", access.has("recruiting", "EDIT"));
		return c;
	}

	/** Body: {@code stage} and, for REJECTED, {@code reason}. Moving to HIRED is done by accepting an offer. */
	@Transactional
	public Map<String, Object> move(UUID id, Map<String, Object> b) {
		require("EDIT");
		Map<String, Object> c = candidateRow(id);
		String stage = str(b.get("stage"));
		if (!in(STAGES, stage) && !"REJECTED".equals(stage)) {
			throw problem("stage", "Unknown stage");
		}
		if ("HIRED".equals(stage)) {
			throw new ConflictException("Accept their offer to mark them hired");
		}
		if ("HIRED".equals(c.get("stage"))) {
			throw new ConflictException("This candidate is already hired");
		}
		String reason = str(b.get("reason"));
		if ("REJECTED".equals(stage) && (reason == null || reason.isBlank())) {
			throw problem("reason", "Say why");
		}
		jdbc.sql("update candidate set stage = :s, rejected_reason = :r, stage_changed_at = now() where id = :id").param("s", stage)
				.param("r", "REJECTED".equals(stage) ? reason.strip() : null, java.sql.Types.VARCHAR).param("id", id).update();
		event(id, "REJECTED".equals(stage) ? "Rejected · " + reason.strip() : "Moved to " + label(stage));
		return candidate(id);
	}

	@Transactional
	public Map<String, Object> updateCandidate(UUID id, Map<String, Object> b) {
		require("EDIT");
		candidateRow(id);
		jdbc.sql("""
				update candidate set email = :e, phone = :p, current_company = :co, current_title = :ti, experience_years = :x, notice_days = :nd, expected_ctc = :ctc,
				  notes = :notes where id = :id""").param("e", blankNull(str(b.get("email"))), java.sql.Types.VARCHAR)
				.param("p", blankNull(str(b.get("phone"))), java.sql.Types.VARCHAR).param("co", blankNull(str(b.get("company"))), java.sql.Types.VARCHAR)
				.param("ti", blankNull(str(b.get("title"))), java.sql.Types.VARCHAR).param("x", dec(b.get("experience")), java.sql.Types.NUMERIC)
				.param("nd", b.get("noticeDays") == null || str(b.get("noticeDays")).isBlank() ? null : Integer.parseInt(str(b.get("noticeDays"))), java.sql.Types.INTEGER)
				.param("ctc", dec(b.get("expectedCtc")), java.sql.Types.NUMERIC).param("notes", blankNull(str(b.get("notes"))), java.sql.Types.VARCHAR).param("id", id)
				.update();
		return candidate(id);
	}

	// ================================================================== interviews

	/** Body: {@code round, at (ISO date-time), durationMin, mode, place, panel: [employee ids]}. */
	@Transactional
	public Map<String, Object> schedule(UUID candidateId, Map<String, Object> b) {
		require("EDIT");
		Map<String, Object> c = candidateRow(candidateId);
		String round = str(b.get("round"));
		if (round == null || round.isBlank()) {
			throw problem("round", "Name the round, e.g. Technical 1");
		}
		OffsetDateTime at;
		try {
			at = java.time.LocalDateTime.parse(str(b.get("at"))).atZone(IST).toOffsetDateTime();
		}
		catch (RuntimeException e) {
			throw problem("at", "Pick a date and time");
		}
		List<UUID> panel = new ArrayList<>();
		if (b.get("panel") instanceof List<?> l) {
			l.forEach(x -> panel.add(UUID.fromString(x.toString())));
		}
		if (panel.isEmpty()) {
			throw problem("panel", "Add at least one interviewer");
		}
		String mode = in(Set.of("Video", "In person", "Phone"), str(b.get("mode"))) ? str(b.get("mode")) : "Video";
		int dur = b.get("durationMin") == null ? 45 : Integer.parseInt(str(b.get("durationMin")));
		UUID id = jdbc.sql("""
				insert into interview (candidate_id, round, scheduled_at, duration_min, mode, place, panel, created_by)
				values (:c, :r, :at, :d, :m, :p, cast(:panel as uuid[]), :by) returning id""").param("c", candidateId).param("r", round.strip()).param("at", at).param("d", dur)
				.param("m", mode).param("p", blankNull(str(b.get("place"))), java.sql.Types.VARCHAR)
				.param("panel", "{" + String.join(",", panel.stream().map(UUID::toString).toList()) + "}").param("by", me(), java.sql.Types.OTHER).query(UUID.class).single();
		if (STAGES.indexOf((String) c.get("stage")) < STAGES.indexOf("INTERVIEW")) {
			jdbc.sql("update candidate set stage = 'INTERVIEW', stage_changed_at = now() where id = :id").param("id", candidateId).update();
		}
		event(candidateId, round.strip() + " scheduled for " + WHEN.format(at.atZoneSameInstant(IST)));
		notifier.inApp(panel, "RECRUITING", "You're interviewing " + c.get("name"), round.strip() + " · " + WHEN.format(at.atZoneSameInstant(IST)) + " · " + mode,
				"/me/interviews");
		return candidate(candidateId);
	}

	@Transactional
	public Map<String, Object> setInterviewStatus(UUID id, String status) {
		require("EDIT");
		if (!Set.of("SCHEDULED", "DONE", "CANCELLED").contains(status)) {
			throw problem("status", "Unknown status");
		}
		UUID cand = jdbc.sql("""
				update interview i set status = :s from candidate c join job_opening j on j.id = c.job_id
				where i.id = :id and c.id = i.candidate_id and j.org_id = :org returning i.candidate_id""").param("s", status).param("id", id).param("org", org())
				.query(UUID.class).optional().orElseThrow(() -> new NotFoundException("Interview not found"));
		event(cand, "Interview " + ("DONE".equals(status) ? "done" : "CANCELLED".equals(status) ? "cancelled" : "rescheduled"));
		return candidate(cand);
	}

	/** Upcoming (or all, for one candidate) interviews with feedback so far. */
	private List<Map<String, Object>> interviews(boolean upcoming, UUID candidate) {
		return jdbc.sql("""
				select i.id, i.round, i.scheduled_at, i.duration_min, i.mode, i.place, i.status, c.id, c.name, j.title,
				       (select string_agg(e.display_name, ', ') from employee e where e.id = any(i.panel)), i.panel::text[],
				       (select count(*) from interview_feedback f where f.interview_id = i.id)
				from interview i join candidate c on c.id = i.candidate_id join job_opening j on j.id = c.job_id
				where j.org_id = :org and (cast(:c as uuid) is null or c.id = :c) and (not :up or (i.status = 'SCHEDULED' and i.scheduled_at > now() - interval '2 hours'))
				order by i.scheduled_at""" + (upcoming ? " limit 30" : " desc")).param("org", org()).param("c", candidate, java.sql.Types.OTHER).param("up", upcoming)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("round", rs.getString(2));
					m.put("at", rs.getObject(3, OffsetDateTime.class));
					m.put("durationMin", rs.getInt(4));
					m.put("mode", rs.getString(5));
					m.put("place", rs.getString(6));
					m.put("status", rs.getString(7));
					m.put("candidate", Map.of("id", rs.getObject(8, UUID.class), "name", rs.getString(9)));
					m.put("job", rs.getString(10));
					m.put("panel", rs.getString(11) == null ? "" : rs.getString(11));
					m.put("panelIds", Arrays.asList((String[]) rs.getArray(12).getArray()));
					m.put("feedbackCount", rs.getLong(13));
					m.put("feedback", candidate == null ? List.of() : feedback(rs.getObject(1, UUID.class)));
					return m;
				}).list();
	}

	private List<Map<String, Object>> feedback(UUID interview) {
		return jdbc.sql("""
				select e.display_name, f.rating, f.recommendation, f.notes, f.submitted_at, f.employee_id from interview_feedback f join employee e on e.id = f.employee_id
				where f.interview_id = :i order by f.submitted_at""").param("i", interview).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("by", rs.getString(1));
			m.put("rating", rs.getInt(2));
			m.put("recommendation", rs.getString(3));
			m.put("notes", rs.getString(4));
			m.put("at", rs.getObject(5, OffsetDateTime.class));
			m.put("employeeId", rs.getObject(6, UUID.class));
			return m;
		}).list();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> allInterviews() {
		require("VIEW");
		return Map.of("items", interviews(true, null));
	}

	/** Interviews the caller sits on, with whether they've left feedback. Anyone can see their own. */
	@Transactional(readOnly = true)
	public Map<String, Object> myInterviews() {
		UUID me = currentUser.requireEmployeeId();
		List<Map<String, Object>> rows = jdbc.sql("""
				select i.id, i.round, i.scheduled_at, i.duration_min, i.mode, i.place, i.status, c.id, c.name, j.title, c.resume_key is not null,
				       f.rating, f.recommendation, f.notes
				from interview i join candidate c on c.id = i.candidate_id join job_opening j on j.id = c.job_id
				left join interview_feedback f on f.interview_id = i.id and f.employee_id = :me
				where j.org_id = :org and :me = any(i.panel) and i.status <> 'CANCELLED' order by i.scheduled_at desc limit 100""").param("org", org()).param("me", me)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("round", rs.getString(2));
					m.put("at", rs.getObject(3, OffsetDateTime.class));
					m.put("durationMin", rs.getInt(4));
					m.put("mode", rs.getString(5));
					m.put("place", rs.getString(6));
					m.put("status", rs.getString(7));
					m.put("candidate", Map.of("id", rs.getObject(8, UUID.class), "name", rs.getString(9), "hasResume", rs.getBoolean(11)));
					m.put("job", rs.getString(10));
					m.put("myFeedback", rs.getObject(12) == null ? null : Map.of("rating", rs.getInt(12), "recommendation", rs.getString(13), "notes", nz(rs.getString(14))));
					return m;
				}).list();
		return Map.of("items", rows);
	}

	/** Body: {@code rating 1–5, recommendation, notes}. Only panelists, once the interview has started. */
	@Transactional
	public Map<String, Object> giveFeedback(UUID interviewId, Map<String, Object> b) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> iv = jdbc.sql("""
				select i.candidate_id, c.name, i.round, i.scheduled_at, j.recruiter_id from interview i join candidate c on c.id = i.candidate_id join job_opening j on j.id = c.job_id
				where i.id = :i and j.org_id = :org and :me = any(i.panel)""").param("i", interviewId).param("org", org()).param("me", me)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("cand", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("round", rs.getString(3));
					m.put("at", rs.getObject(4, OffsetDateTime.class));
					m.put("rec", rs.getObject(5, UUID.class));
					return m;
				}).optional().orElseThrow(() -> new ForbiddenException("You aren't on this interview's panel"));
		if (((OffsetDateTime) iv.get("at")).isAfter(OffsetDateTime.now())) {
			throw new ConflictException("Leave feedback after the interview");
		}
		int rating;
		try {
			rating = Integer.parseInt(str(b.get("rating")));
		}
		catch (RuntimeException e) {
			throw problem("rating", "Pick a rating");
		}
		if (rating < 1 || rating > 5) {
			throw problem("rating", "Pick a rating");
		}
		String rec = str(b.get("recommendation"));
		if (rec == null || !RECS.contains(rec)) {
			throw problem("recommendation", "Say whether to hire");
		}
		jdbc.sql("""
				insert into interview_feedback (interview_id, employee_id, rating, recommendation, notes) values (:i, :me, :r, :rec, :n)
				on conflict (interview_id, employee_id) do update set rating = :r, recommendation = :rec, notes = :n, submitted_at = now()""").param("i", interviewId).param("me", me)
				.param("r", rating).param("rec", rec).param("n", blankNull(str(b.get("notes"))), java.sql.Types.VARCHAR).update();
		jdbc.sql("update interview set status = 'DONE' where id = :i and status = 'SCHEDULED'").param("i", interviewId).update();
		event((UUID) iv.get("cand"), myName() + " left feedback on " + iv.get("round") + " · " + rec.replace('_', ' ').toLowerCase(Locale.ROOT));
		if (iv.get("rec") != null && !iv.get("rec").equals(me)) {
			notifier.inApp(List.of((UUID) iv.get("rec")), "RECRUITING", "Interview feedback in", iv.get("name") + " · " + iv.get("round"), "/recruiting?candidate=" + iv.get("cand"));
		}
		return myInterviews();
	}

	// ================================================================== offers

	/** Body: {@code ctc (₹ lakh), joiningDate, note}. */
	@Transactional
	public Map<String, Object> offer(UUID candidateId, Map<String, Object> b) {
		require("EDIT");
		Map<String, Object> c = candidateRow(candidateId);
		if ("HIRED".equals(c.get("stage")) || "REJECTED".equals(c.get("stage"))) {
			throw new ConflictException("This candidate is " + label((String) c.get("stage")).toLowerCase(Locale.ROOT));
		}
		BigDecimal ctc = dec(b.get("ctc"));
		if (ctc == null || ctc.signum() <= 0) {
			throw problem("ctc", "Enter the offered CTC in lakh");
		}
		LocalDate doj = date(b.get("joiningDate"));
		if (doj == null) {
			throw problem("joiningDate", "Pick a joining date");
		}
		jdbc.sql("update job_offer set status = 'WITHDRAWN', decided_at = now() where candidate_id = :c and status = 'SENT'").param("c", candidateId).update();
		jdbc.sql("insert into job_offer (candidate_id, ctc, joining_date, note, created_by) values (:c, :ctc, :d, :n, :by)").param("c", candidateId).param("ctc", ctc)
				.param("d", doj).param("n", blankNull(str(b.get("note"))), java.sql.Types.VARCHAR).param("by", me(), java.sql.Types.OTHER).update();
		jdbc.sql("update candidate set stage = 'OFFER', stage_changed_at = now() where id = :c").param("c", candidateId).update();
		event(candidateId, "Offer made · ₹" + ctc.stripTrailingZeros().toPlainString() + " L · joining " + doj);
		return candidate(candidateId);
	}

	/** {@code ACCEPTED} hires the candidate (and closes the job when it's filled); {@code DECLINED} or {@code WITHDRAWN} ends the offer. */
	@Transactional
	public Map<String, Object> decideOffer(UUID offerId, String status) {
		require("EDIT");
		if (!Set.of("ACCEPTED", "DECLINED", "WITHDRAWN").contains(status)) {
			throw problem("status", "Unknown answer");
		}
		Map<String, Object> o = jdbc.sql("""
				select o.candidate_id, c.job_id, o.status from job_offer o join candidate c on c.id = o.candidate_id join job_opening j on j.id = c.job_id
				where o.id = :o and j.org_id = :org""").param("o", offerId).param("org", org())
				.query((rs, i) -> Map.<String, Object>of("cand", rs.getObject(1, UUID.class), "job", rs.getObject(2, UUID.class), "st", rs.getString(3))).optional()
				.orElseThrow(() -> new NotFoundException("Offer not found"));
		if (!"SENT".equals(o.get("st"))) {
			throw new ConflictException("This offer is already " + o.get("st").toString().toLowerCase(Locale.ROOT));
		}
		UUID cand = (UUID) o.get("cand");
		jdbc.sql("update job_offer set status = :s, decided_at = now() where id = :o").param("s", status).param("o", offerId).update();
		if ("ACCEPTED".equals(status)) {
			jdbc.sql("update candidate set stage = 'HIRED', stage_changed_at = now() where id = :c").param("c", cand).update();
			event(cand, "Offer accepted · hired");
			long hired = jdbc.sql("select count(*) from candidate where job_id = :j and stage = 'HIRED'").param("j", o.get("job")).query(Long.class).single();
			jdbc.sql("update job_opening set status = 'CLOSED', closed_at = now() where id = :j and openings <= :h and status = 'OPEN'").param("j", o.get("job"))
					.param("h", hired).update();
		}
		else {
			event(cand, "Offer " + status.toLowerCase(Locale.ROOT));
		}
		return candidate(cand);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> offers() {
		require("VIEW");
		return Map.of("items", jdbc.sql("""
				select o.id, c.id, c.name, j.title, o.ctc, o.joining_date, o.status, o.created_at, o.decided_at from job_offer o join candidate c on c.id = o.candidate_id
				join job_opening j on j.id = c.job_id where j.org_id = :org order by o.created_at desc limit 200""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("candidate", Map.of("id", rs.getObject(2, UUID.class), "name", rs.getString(3)));
			m.put("job", rs.getString(4));
			m.put("ctc", rs.getBigDecimal(5));
			m.put("joiningDate", rs.getObject(6, LocalDate.class));
			m.put("status", rs.getString(7));
			m.put("createdAt", rs.getObject(8, OffsetDateTime.class));
			m.put("decidedAt", rs.getObject(9, OffsetDateTime.class));
			return m;
		}).list());
	}

	// ================================================================== helpers

	private Map<String, Object> candidateRow(UUID id) {
		return jdbc.sql("""
				select c.id, c.name, c.email, c.phone, c.source, r.display_name, c.current_company, c.current_title, c.experience_years, c.notice_days, c.expected_ctc,
				       c.stage, c.rejected_reason, c.resume_key, c.resume_name, c.notes, c.created_at, c.stage_changed_at, j.id, j.title, d.name, l.name, d.id, l.id,
				       j.hiring_manager_id, hm.display_name
				from candidate c join job_opening j on j.id = c.job_id left join employee r on r.id = c.referred_by left join employee hm on hm.id = j.hiring_manager_id left join department d on d.id = j.department_id
				left join location l on l.id = j.location_id where c.id = :id and j.org_id = :org""").param("id", id).param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("email", rs.getString(3));
			m.put("phone", rs.getString(4));
			m.put("source", rs.getString(5));
			m.put("referredBy", rs.getString(6));
			m.put("company", rs.getString(7));
			m.put("title", rs.getString(8));
			m.put("experience", rs.getBigDecimal(9));
			m.put("noticeDays", rs.getObject(10));
			m.put("expectedCtc", rs.getBigDecimal(11));
			m.put("stage", rs.getString(12));
			m.put("rejectedReason", rs.getString(13));
			m.put("resumeKey", rs.getString(14));
			m.put("resumeName", rs.getString(15));
			m.put("notes", rs.getString(16));
			m.put("createdAt", rs.getObject(17, OffsetDateTime.class));
			m.put("since", rs.getObject(18, OffsetDateTime.class));
			m.put("job", Map.of("id", rs.getObject(19, UUID.class), "title", rs.getString(20), "department", nz(rs.getString(21)), "location", nz(rs.getString(22)),
					"departmentId", rs.getObject(23) == null ? "" : rs.getObject(23).toString(), "locationId", rs.getObject(24) == null ? "" : rs.getObject(24).toString(), "hiringManagerId",
					rs.getObject(25) == null ? "" : rs.getObject(25).toString(), "hiringManager", nz(rs.getString(26))));
			return m;
		}).optional().orElseThrow(() -> new NotFoundException("Candidate not found"));
	}

	private void event(UUID candidate, String text) {
		jdbc.sql("insert into candidate_event (candidate_id, text, actor_id) values (:c, :t, :by)").param("c", candidate).param("t", text).param("by", me(), java.sql.Types.OTHER)
				.update();
	}

	private String myName() {
		UUID me = me();
		return me == null ? "System" : jdbc.sql("select display_name from employee where id = :e").param("e", me).query(String.class).optional().orElse("Someone");
	}

	static String label(String stage) {
		return switch (stage) {
			case "APPLIED" -> "Applied";
			case "SCREENING" -> "Screening";
			case "INTERVIEW" -> "Interview";
			case "OFFER" -> "Offer";
			case "HIRED" -> "Hired";
			default -> "Rejected";
		};
	}

	private void require(String level) {
		if (!access.has("recruiting", level)) {
			throw new ForbiddenException("VIEW".equals(level) ? "Your role can't see recruiting" : "Your role can't change recruiting");
		}
	}

	private static UnprocessableException problem(String field, String msg) {
		return new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", msg)));
	}

	private static BigDecimal dec(Object v) {
		if (v == null || String.valueOf(v).isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(String.valueOf(v).trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static LocalDate date(Object v) {
		if (v == null || String.valueOf(v).isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(String.valueOf(v));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static UUID uuid(Object v) {
		if (v == null || String.valueOf(v).isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(String.valueOf(v));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean in(java.util.Collection<String> set, String v) {
		return v != null && set.contains(v);
	}

	private static String blankNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
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
