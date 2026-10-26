package com.ivoyant.hrms.leave.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.LeavePolicyChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConfirmRequiredException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.leave.LeaveTypeUsage;
import com.ivoyant.hrms.leave.LeaveTypeView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Leave &amp; attendance → Leave policy. A leave type's code is fixed once anything uses it; built-in types (CL, SL, EL, CO, ML,
 * PL) can be edited and switched off but never deleted. Changes apply to new requests and future credits, never to balances already
 * credited or requests already made.
 */
@Service
public class LeaveTypeService {

	private static final String ENTITY = "LeaveType";
	private static final Pattern CODE = Pattern.compile("^[A-Z]{2,4}$");
	private static final Set<String> ACCRUALS = Set.of("YEARLY", "MONTHLY", "QUARTERLY", "EVENT", "EARNED");

	/** The type with its usage flag; accrual, carry, countAs and gender are lower-case words, as the screen uses them. */
	public record LeaveType(UUID id, String code, String name, String shortLabel, int colorIndex, boolean active, boolean system, String accrual,
			BigDecimal quota, String carry, BigDecimal carryMax, int expiryDays, boolean encashable, BigDecimal encashMax, String countAs,
			boolean paid, String gender, boolean halfDay, int noticeDays, int maxConsecutive, int docAfterDays, int minServiceDays,
			boolean inUse, Long version, BigDecimal available) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final LeaveTypeUsage usage;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public LeaveTypeService(JdbcClient jdbc, CurrentUser currentUser, LeaveTypeUsage usage, Audit audit, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.usage = usage;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reads

	private LeaveType map(java.sql.ResultSet rs) throws java.sql.SQLException {
		UUID id = rs.getObject("id", UUID.class);
		return new LeaveType(id, rs.getString("code"), rs.getString("name"), rs.getString("short_label"), rs.getInt("color_index"), rs.getBoolean("active"),
				rs.getBoolean("system"), rs.getString("accrual").toLowerCase(Locale.ROOT), rs.getBigDecimal("quota_days"),
				rs.getString("carry").toLowerCase(Locale.ROOT), rs.getBigDecimal("carry_max"), rs.getInt("expiry_days"), rs.getBoolean("encashable"),
				rs.getBigDecimal("encash_max"), rs.getString("count_as").toLowerCase(Locale.ROOT), rs.getBoolean("paid"),
				rs.getString("gender").toLowerCase(Locale.ROOT), rs.getBoolean("half_day"), rs.getInt("notice_days"), rs.getInt("max_consecutive"),
				rs.getInt("doc_after_days"), rs.getInt("min_service_days"), usage.inUse(id), rs.getLong("version"), null);
	}

	@Transactional(readOnly = true)
	public List<LeaveType> list(Boolean active) {
		return jdbc.sql("select * from leave_type where org_id = :org" + (active == null ? "" : " and active = :a") + " order by sort, name")
				.param("org", currentUser.orgId()).param("a", active, java.sql.Types.BOOLEAN).query((rs, i) -> map(rs)).list();
	}

	/** The apply picker: active types the caller is eligible for today. */
	@Transactional(readOnly = true)
	public List<LeaveType> forMe() {
		Person me = person(currentUser.requireEmployeeId());
		return list(true).stream().filter(t -> eligible(me, t.gender(), t.minServiceDays(), LocalDate.now())).toList();
	}

	@Transactional(readOnly = true)
	public LeaveType get(UUID id) {
		return jdbc.sql("select * from leave_type where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Leave type " + id + " not found"));
	}

	public LeaveTypeView view(LeaveType t) {
		return new LeaveTypeView(t.id(), t.code(), t.name(), t.shortLabel(), t.colorIndex(), t.active(), t.system(), t.accrual().toUpperCase(Locale.ROOT), t.quota(),
				t.carry().toUpperCase(Locale.ROOT), t.carryMax(), t.expiryDays(), t.encashable(), t.encashMax(), t.countAs().toUpperCase(Locale.ROOT),
				t.paid(), t.gender().toUpperCase(Locale.ROOT), t.halfDay(), t.noticeDays(), t.maxConsecutive(), t.docAfterDays(), t.minServiceDays());
	}

	// ================================================================== create / update

	@Transactional
	public LeaveType create(Map<String, Object> body) {
		Fields f = Fields.from(body, null);
		validate(f, null);
		int sort = jdbc.sql("select coalesce(max(sort), 0) + 10 from leave_type where org_id = :org").param("org", currentUser.orgId()).query(Integer.class).single();
		UUID id = jdbc.sql("""
				insert into leave_type (org_id, code, name, short_label, color_index, active, system, accrual, quota_days, carry, carry_max, expiry_days,
				    encashable, encash_max, count_as, paid, gender, half_day, notice_days, max_consecutive, doc_after_days, min_service_days, sort,
				    created_by, updated_by)
				values (:org, :code, :name, :short, :color, :active, false, :accrual, :quota, :carry, :carryMax, :expiry, :enc, :encMax, :countAs, :paid,
				    :gender, :half, :notice, :maxRun, :doc, :service, :sort, :by, :by) returning id""")
				.paramSource(f.params(currentUser.orgId(), sort, currentUser.employeeId())).query(UUID.class).single();
		LeaveType out = get(id);
		audit.created("leave", ENTITY, id, out);
		events.publishEvent(new LeavePolicyChangedEvent(id));
		return out;
	}

	/** The side panel's Save: every field of the type. Fields left out keep their value. */
	@Transactional
	public LeaveType update(UUID id, Map<String, Object> body, String ifMatch, boolean force) {
		LeaveType cur = get(id);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(cur.version()))) {
			throw new StaleException("Someone else changed this leave type. Reload and try again.");
		}
		Fields f = Fields.from(body, cur);
		validate(f, id);
		if (cur.inUse() && !f.code.equals(cur.code())) {
			throw invalid("code", "CODE_LOCKED", cur.code() + " is used by existing requests, so its code can't change");
		}
		boolean toggledEarned = cur.accrual().equals("earned") != f.accrual.equalsIgnoreCase("earned");
		if (cur.inUse() && toggledEarned && !force) {
			throw new ConfirmRequiredException("Changing " + cur.code() + " to or from earned leave changes what its balances mean.",
					List.of("Existing balances and requests keep their numbers", "New credits follow the new rule"));
		}
		if (cur.active() && !f.active && jdbc.sql("select count(*) from leave_type where org_id = :org and active and id <> :id").param("org", currentUser.orgId())
				.param("id", id).query(Long.class).single() == 0) {
			throw invalid("active", "LAST_ACTIVE", "Keep at least one leave type active");
		}
		jdbc.sql("""
				update leave_type set code = :code, name = :name, short_label = :short, color_index = :color, active = :active, accrual = :accrual,
				    quota_days = :quota, carry = :carry, carry_max = :carryMax, expiry_days = :expiry, encashable = :enc, encash_max = :encMax,
				    count_as = :countAs, paid = :paid, gender = :gender, half_day = :half, notice_days = :notice, max_consecutive = :maxRun,
				    doc_after_days = :doc, min_service_days = :service, updated_at = now(), updated_by = :by, version = version + 1
				where id = :id and org_id = :org""").paramSource(f.params(currentUser.orgId(), cur.colorIndex(), currentUser.employeeId()).addValue("id", id)).update();
		LeaveType after = get(id);
		audit.updated("leave", ENTITY, id, diff(cur), diff(after));
		events.publishEvent(new LeavePolicyChangedEvent(id));
		return after;
	}

	/** The inline Days edit in the table. Earned leave has no yearly days. */
	@Transactional
	public LeaveType setQuota(UUID id, Object quota) {
		LeaveType cur = get(id);
		if ("earned".equals(cur.accrual())) {
			throw invalid("quota", "INVALID", cur.name() + " is earned, so it has no days per year");
		}
		BigDecimal q = decimal(quota, "quota");
		if (q == null || q.signum() <= 0) {
			throw invalid("quota", "REQUIRED", "event".equals(cur.accrual()) ? "Set days per event" : "Set days per year");
		}
		jdbc.sql("update leave_type set quota_days = :q, updated_at = now(), updated_by = :by, version = version + 1 where id = :id")
				.param("q", q).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		LeaveType after = get(id);
		audit.updated("leave", ENTITY, id, Map.of("quota", String.valueOf(cur.quota())), Map.of("quota", String.valueOf(after.quota())));
		events.publishEvent(new LeavePolicyChangedEvent(id));
		return after;
	}

	@Transactional
	public void delete(UUID id) {
		LeaveType cur = get(id);
		if (cur.system()) {
			throw invalid("id", "SYSTEM_TYPE", cur.name() + " is a built-in leave type. Switch it off instead of deleting it");
		}
		if (cur.inUse()) {
			throw new InUseException(cur.name() + " is used by leave requests or balances. Switch it off instead.", Map.of("type", cur.code()));
		}
		jdbc.sql("delete from leave_type where id = :id").param("id", id).update();
		audit.deleted("leave", ENTITY, id, diff(cur));
		events.publishEvent(new LeavePolicyChangedEvent(id));
	}

	private Map<String, Object> diff(LeaveType t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", t.code());
		m.put("name", t.name());
		m.put("active", t.active());
		m.put("accrual", t.accrual());
		m.put("quota", t.quota());
		m.put("carry", t.carry() + (t.carryMax().signum() > 0 ? " " + t.carryMax() : ""));
		m.put("encash", t.encashable() ? "yes, max " + t.encashMax() : "no");
		m.put("countAs", t.countAs());
		m.put("gender", t.gender());
		m.put("rules", "notice " + t.noticeDays() + ", max run " + t.maxConsecutive() + ", doc after " + t.docAfterDays() + ", service " + t.minServiceDays());
		return m;
	}

	// ================================================================== rules

	private void validate(Fields f, UUID self) {
		List<ErrorItem> errors = new ArrayList<>();
		if (f.name == null || f.name.isBlank()) {
			errors.add(ErrorItem.field("name", "REQUIRED", "Enter a leave type name"));
		}
		if (!CODE.matcher(f.code == null ? "" : f.code).matches()) {
			errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–4 letters"));
		}
		if (!ACCRUALS.contains(f.accrual)) {
			errors.add(ErrorItem.field("accrual", "INVALID", "Accrual must be yearly, monthly, quarterly, event or earned"));
		}
		else if (!"EARNED".equals(f.accrual) && (f.quota == null || f.quota.signum() <= 0)) {
			errors.add(ErrorItem.field("quota", "REQUIRED", "EVENT".equals(f.accrual) ? "Set days per event" : "Set days per year"));
		}
		if (f.shortLabel == null || f.shortLabel.isBlank()) {
			errors.add(ErrorItem.field("short", "REQUIRED", "Enter a short label"));
		}
		if (f.colorIndex < 0 || f.colorIndex > 9) {
			errors.add(ErrorItem.field("colorIndex", "BAD_COLOR", "Pick one of the 10 colours"));
		}
		if (!Set.of("NONE", "UPTO", "ALL").contains(f.carry)) {
			errors.add(ErrorItem.field("carry", "INVALID", "Carry forward must be none, upto or all"));
		}
		if (!Set.of("WORKING", "CALENDAR").contains(f.countAs)) {
			errors.add(ErrorItem.field("countAs", "INVALID", "Count as must be working or calendar days"));
		}
		if (!Set.of("ALL", "FEMALE", "MALE").contains(f.gender)) {
			errors.add(ErrorItem.field("gender", "INVALID", "Eligible must be all, female or male"));
		}
		if (f.noticeDays < 0 || f.maxConsecutive < 0 || f.docAfterDays < 0 || f.minServiceDays < 0 || f.expiryDays < 0) {
			errors.add(ErrorItem.field("noticeDays", "INVALID", "Days can't be negative"));
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		boolean taken = jdbc.sql("select count(*) from leave_type where org_id = :org and code = :c and (cast(:self as uuid) is null or id <> cast(:self as uuid))")
				.param("org", currentUser.orgId()).param("c", f.code).param("self", self, java.sql.Types.OTHER).query(Long.class).single() > 0;
		if (taken) {
			throw new CodedConflictException("CODE_TAKEN", "Code " + f.code + " is already used");
		}
		boolean nameTaken = jdbc.sql("select count(*) from leave_type where org_id = :org and lower(name) = lower(:n) and (cast(:self as uuid) is null or id <> cast(:self as uuid))")
				.param("org", currentUser.orgId()).param("n", f.name).param("self", self, java.sql.Types.OTHER).query(Long.class).single() > 0;
		if (nameTaken) {
			throw new CodedConflictException("NAME_TAKEN", f.name + " already exists");
		}
	}

	/** The request body read into the values the table keeps (normalised: earned has no quota or carry, only earned expires). */
	private static final class Fields {

		String code;
		String name;
		String shortLabel;
		int colorIndex;
		boolean active;
		String accrual;
		BigDecimal quota;
		String carry;
		BigDecimal carryMax;
		int expiryDays;
		boolean encashable;
		BigDecimal encashMax;
		String countAs;
		boolean paid;
		String gender;
		boolean halfDay;
		int noticeDays;
		int maxConsecutive;
		int docAfterDays;
		int minServiceDays;

		static Fields from(Map<String, Object> b, LeaveType cur) {
			Fields f = new Fields();
			f.name = b.containsKey("name") ? text(b.get("name")) : cur == null ? null : cur.name();
			String c = b.containsKey("code") ? text(b.get("code")) : cur == null ? autoCode(f.name) : cur.code();
			f.code = c == null ? null : c.toUpperCase(Locale.ROOT);
			Object sl = b.containsKey("short") ? b.get("short") : b.containsKey("shortLabel") ? b.get("shortLabel") : null;
			f.shortLabel = sl != null ? text(sl) : cur == null ? f.name : cur.shortLabel();
			f.colorIndex = b.containsKey("colorIndex") ? integer(b.get("colorIndex"), "colorIndex") : cur == null ? 0 : cur.colorIndex();
			f.active = b.containsKey("active") ? bool(b.get("active")) : cur == null || cur.active();
			f.accrual = upper(b.containsKey("accrual") ? b.get("accrual") : cur == null ? "yearly" : cur.accrual());
			f.quota = b.containsKey("quota") ? decimal(b.get("quota"), "quota") : cur == null ? null : cur.quota();
			f.carry = upper(b.containsKey("carry") ? b.get("carry") : cur == null ? "none" : cur.carry());
			f.carryMax = b.containsKey("carryMax") ? orZero(decimal(b.get("carryMax"), "carryMax")) : cur == null ? BigDecimal.ZERO : cur.carryMax();
			f.expiryDays = b.containsKey("expiryDays") ? integer(b.get("expiryDays"), "expiryDays") : cur == null ? 0 : cur.expiryDays();
			f.encashable = b.containsKey("encashable") ? bool(b.get("encashable")) : cur != null && cur.encashable();
			f.encashMax = b.containsKey("encashMax") ? orZero(decimal(b.get("encashMax"), "encashMax")) : cur == null ? BigDecimal.ZERO : cur.encashMax();
			f.countAs = upper(b.containsKey("countAs") ? b.get("countAs") : cur == null ? "working" : cur.countAs());
			f.paid = b.containsKey("paid") ? bool(b.get("paid")) : cur == null || cur.paid();
			f.gender = upper(b.containsKey("gender") ? b.get("gender") : cur == null ? "all" : cur.gender());
			f.halfDay = b.containsKey("halfDay") ? bool(b.get("halfDay")) : cur == null || cur.halfDay();
			f.noticeDays = b.containsKey("noticeDays") ? integer(b.get("noticeDays"), "noticeDays") : cur == null ? 0 : cur.noticeDays();
			f.maxConsecutive = b.containsKey("maxConsecutive") ? integer(b.get("maxConsecutive"), "maxConsecutive") : cur == null ? 0 : cur.maxConsecutive();
			f.docAfterDays = b.containsKey("docAfterDays") ? integer(b.get("docAfterDays"), "docAfterDays") : cur == null ? 0 : cur.docAfterDays();
			f.minServiceDays = b.containsKey("minServiceDays") ? integer(b.get("minServiceDays"), "minServiceDays") : cur == null ? 0 : cur.minServiceDays();
			// the type decides what the other fields mean
			if ("EARNED".equals(f.accrual)) {
				f.quota = null;
				f.carry = "NONE";
			}
			else {
				f.expiryDays = 0;
			}
			if (!"UPTO".equals(f.carry)) {
				f.carryMax = BigDecimal.ZERO;
			}
			if (!f.encashable) {
				f.encashMax = BigDecimal.ZERO;
			}
			return f;
		}

		org.springframework.jdbc.core.namedparam.MapSqlParameterSource params(UUID org, int sortOrColor, UUID actor) {
			return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("org", org).addValue("code", code).addValue("name", name)
					.addValue("short", shortLabel).addValue("color", colorIndex).addValue("active", active).addValue("accrual", accrual)
					.addValue("quota", quota).addValue("carry", carry).addValue("carryMax", carryMax).addValue("expiry", expiryDays)
					.addValue("enc", encashable).addValue("encMax", encashMax).addValue("countAs", countAs).addValue("paid", paid)
					.addValue("gender", gender).addValue("half", halfDay).addValue("notice", noticeDays).addValue("maxRun", maxConsecutive)
					.addValue("doc", docAfterDays).addValue("service", minServiceDays).addValue("sort", sortOrColor).addValue("by", actor, java.sql.Types.OTHER);
		}

		private static String autoCode(String name) {
			if (name == null) {
				return null;
			}
			String letters = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
			String[] words = name.toUpperCase(Locale.ROOT).split("[^A-Z]+");
			StringBuilder initials = new StringBuilder();
			for (String w : words) {
				if (!w.isEmpty()) {
					initials.append(w.charAt(0));
				}
			}
			String code = initials.length() >= 2 ? initials.toString() : letters;
			return code.length() > 4 ? code.substring(0, 4) : code;
		}

		private static String upper(Object v) {
			return v == null ? "" : v.toString().trim().toUpperCase(Locale.ROOT);
		}

		private static String text(Object v) {
			return v == null || v.toString().isBlank() ? null : v.toString().trim().replaceAll("\\s+", " ");
		}

		private static boolean bool(Object v) {
			return Boolean.parseBoolean(String.valueOf(v));
		}

		private static BigDecimal orZero(BigDecimal v) {
			return v == null ? BigDecimal.ZERO : v;
		}

		private static int integer(Object v, String field) {
			try {
				return v instanceof Number n ? n.intValue() : v == null || v.toString().isBlank() ? 0 : (int) Double.parseDouble(v.toString().trim());
			}
			catch (NumberFormatException ex) {
				throw invalid(field, "INVALID", "'" + v + "' is not a number");
			}
		}
	}

	private static BigDecimal decimal(Object v, String field) {
		if (v == null || v.toString().isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(v.toString().trim()).setScale(1, RoundingMode.HALF_UP);
		}
		catch (NumberFormatException ex) {
			throw invalid(field, "INVALID", "'" + v + "' is not a number");
		}
	}

	// ================================================================== eligibility (also used by the policy facade)

	/** What eligibility needs to know about a person. */
	record Person(UUID id, String gender, LocalDate joined) {
	}

	Person person(UUID employeeId) {
		return jdbc.sql("select id, lower(custom ->> 'gender'), joining_date from employee where id = :id").param("id", employeeId)
				.query((rs, i) -> new Person(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class))).optional()
				.orElseThrow(() -> new NotFoundException("Employee " + employeeId + " not found"));
	}

	/** Gender and length of service. A type for one gender is not offered to people whose gender is not recorded. */
	static boolean eligible(Person p, String gender, int minServiceDays, LocalDate on) {
		String g = gender == null ? "all" : gender.toLowerCase(Locale.ROOT);
		if (!g.equals("all") && !g.equals(p.gender())) {
			return false;
		}
		return minServiceDays <= 0 || p.joined() != null && ChronoUnit.DAYS.between(p.joined(), on) >= minServiceDays;
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
