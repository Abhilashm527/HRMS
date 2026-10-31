package com.ivoyant.hrms.requests.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The yearly income-tax declaration: the employee picks the old or new regime and, under the old one, declares 80C, 80D, rent, NPS and
 * home-loan interest; HR (requests Approve) or payroll approvers verify it or send it back. A verified declaration is locked for the
 * year. Amounts are capped at the statutory limits.
 */
@Service
public class TaxDeclarationService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	/** Field → [label, yearly cap]. Rent is monthly, so its cap is per month. */
	static final Map<String, Object[]> FIELDS = new LinkedHashMap<>();

	static {
		FIELDS.put("sec80c", new Object[] { "80C investments", new BigDecimal("150000") });
		FIELDS.put("sec80d", new Object[] { "80D health insurance", new BigDecimal("100000") });
		FIELDS.put("rentMonthly", new Object[] { "Rent paid per month", new BigDecimal("500000") });
		FIELDS.put("nps", new Object[] { "NPS 80CCD(1B)", new BigDecimal("50000") });
		FIELDS.put("homeLoan", new Object[] { "Home-loan interest", new BigDecimal("200000") });
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Notifier notifier;
	private final Audit audit;

	public TaxDeclarationService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Notifier notifier, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.notifier = notifier;
		this.audit = audit;
	}

	/** The Indian financial year a day falls in, e.g. 2026-27. */
	static String fy(LocalDate d) {
		int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
		return y + "-" + String.format("%02d", (y + 1) % 100);
	}

	public boolean canVerifyTax() {
		return canVerify();
	}

	boolean canVerify() {
		return access.has("requests", "APPROVE") || access.has("payroll", "APPROVE");
	}

	private static final String SQL = """
			select t.*, e.display_name, e.first_name, e.last_name, e.employee_code, jt.name as emp_title, d.name as dept, db.display_name as decided_by_name
			  from tax_declaration t join employee e on e.id = t.employee_id left join job_title jt on jt.id = e.title_id
			  left join department d on d.id = e.department_id left join employee db on db.id = t.decided_by
			""";

	private static Map<String, Object> view(ResultSet rs) throws SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", rs.getObject("id", UUID.class));
		m.put("fy", rs.getString("fy"));
		m.put("regime", rs.getString("regime"));
		m.put("sec80c", rs.getBigDecimal("sec80c"));
		m.put("sec80d", rs.getBigDecimal("sec80d"));
		m.put("rentMonthly", rs.getBigDecimal("rent_monthly"));
		m.put("nps", rs.getBigDecimal("nps"));
		m.put("homeLoan", rs.getBigDecimal("home_loan"));
		m.put("status", rs.getString("status"));
		m.put("submittedAt", rs.getObject("submitted_at", OffsetDateTime.class));
		m.put("decidedAt", rs.getObject("decided_at", OffsetDateTime.class));
		m.put("decidedBy", rs.getString("decided_by_name"));
		m.put("decisionNote", rs.getString("decision_note"));
		m.put("version", rs.getLong("version"));
		String f = rs.getString("first_name");
		String l = rs.getString("last_name");
		Map<String, Object> emp = new LinkedHashMap<>();
		emp.put("id", rs.getObject("employee_id", UUID.class));
		emp.put("name", rs.getString("display_name"));
		emp.put("first", f);
		emp.put("initials", ((f == null || f.isEmpty() ? "" : f.substring(0, 1)) + (l == null || l.isEmpty() ? "" : l.substring(0, 1))).toUpperCase());
		emp.put("code", rs.getString("employee_code"));
		emp.put("title", rs.getString("emp_title"));
		emp.put("department", rs.getString("dept"));
		m.put("employee", emp);
		return m;
	}

	/** The caller's declaration for the current financial year (a blank draft when they have none yet). */
	@Transactional(readOnly = true)
	public Map<String, Object> mine() {
		UUID me = currentUser.requireEmployeeId();
		String fy = fy(LocalDate.now(IST));
		Map<String, Object> out = jdbc.sql(SQL + " where t.employee_id = :me and t.fy = :fy").param("me", me).param("fy", fy).query((rs, i) -> view(rs)).optional()
				.orElseGet(() -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", null);
					m.put("fy", fy);
					m.put("regime", "NEW");
					FIELDS.keySet().forEach(k -> m.put(k, BigDecimal.ZERO));
					m.put("status", "DRAFT");
					m.put("version", 0);
					return m;
				});
		out.put("limits", FIELDS.entrySet().stream().map(e -> Map.of("key", e.getKey(), "label", e.getValue()[0], "max", e.getValue()[1])).toList());
		return out;
	}

	/** Saves the caller's declaration; {@code submit=true} sends it to HR. Verified declarations can't change. */
	@Transactional
	public Map<String, Object> save(Map<String, Object> body) {
		UUID me = currentUser.requireEmployeeId();
		UUID org = currentUser.orgId();
		String fy = fy(LocalDate.now(IST));
		String cur = jdbc.sql("select status from tax_declaration where employee_id = :me and fy = :fy").param("me", me).param("fy", fy).query(String.class)
				.optional().orElse(null);
		if ("VERIFIED".equals(cur)) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "LOCKED", "HR already verified your declaration for " + fy + ". Ask HR to reopen it.")));
		}
		String regime = "OLD".equalsIgnoreCase(String.valueOf(body.get("regime"))) ? "OLD" : "NEW";
		List<ErrorItem> errors = new ArrayList<>();
		Map<String, BigDecimal> v = new LinkedHashMap<>();
		for (Map.Entry<String, Object[]> f : FIELDS.entrySet()) {
			BigDecimal x = BigDecimal.ZERO;
			Object raw = body.get(f.getKey());
			if ("OLD".equals(regime) && raw != null && !String.valueOf(raw).isBlank()) {
				try {
					x = new BigDecimal(String.valueOf(raw).replace(",", "").trim());
					if (x.signum() < 0) {
						throw new NumberFormatException();
					}
					if (x.compareTo((BigDecimal) f.getValue()[1]) > 0) {
						errors.add(ErrorItem.field(f.getKey(), "OVER_LIMIT", f.getValue()[0] + " can be at most ₹" + EmployeeRequestService.inr((BigDecimal) f.getValue()[1]).substring(1)));
					}
				}
				catch (NumberFormatException e) {
					errors.add(ErrorItem.field(f.getKey(), "INVALID", "Enter an amount in rupees"));
				}
			}
			v.put(f.getKey(), x);
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		boolean submit = Boolean.parseBoolean(String.valueOf(body.get("submit")));
		String status = submit ? "SUBMITTED" : cur == null || "REJECTED".equals(cur) ? "DRAFT" : cur;
		UUID id = jdbc.sql("""
				insert into tax_declaration (org_id, employee_id, fy, regime, sec80c, sec80d, rent_monthly, nps, home_loan, status, submitted_at)
				values (:org, :me, :fy, :r, :a, :b, :c, :d, :e, :s, case when :submit then now() end)
				on conflict (employee_id, fy) do update set regime = excluded.regime, sec80c = excluded.sec80c, sec80d = excluded.sec80d,
				   rent_monthly = excluded.rent_monthly, nps = excluded.nps, home_loan = excluded.home_loan, status = excluded.status,
				   submitted_at = coalesce(excluded.submitted_at, tax_declaration.submitted_at), decided_by = case when :submit then null else tax_declaration.decided_by end,
				   decided_at = case when :submit then null else tax_declaration.decided_at end, updated_at = now(), version = tax_declaration.version + 1
				returning id""").param("org", org).param("me", me).param("fy", fy).param("r", regime).param("a", v.get("sec80c")).param("b", v.get("sec80d"))
				.param("c", v.get("rentMonthly")).param("d", v.get("nps")).param("e", v.get("homeLoan")).param("s", status).param("submit", submit)
				.query(UUID.class).single();
		if (submit) {
			String name = jdbc.sql("select display_name from employee where id = :me").param("me", me).query(String.class).single();
			List<UUID> hr = jdbc.sql("""
					select distinct u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
					 where u.org_id = :org and u.status = 'ACTIVE' and r.key in ('hr_admin', 'super_admin', 'finance') and u.employee_id <> :me""")
					.param("org", org).param("me", me).query(UUID.class).list();
			notifier.inApp(hr, "REQUEST", name + " submitted a tax declaration", fy + " · " + ("OLD".equals(regime) ? "Old" : "New") + " regime", "/inbox?tab=decl");
			audit.publish("requests", "TaxDeclaration", id, "SUBMITTED", null, Map.of("detail", fy + " · " + regime));
		}
		return mine();
	}

	/** Submitted declarations waiting for verification (or, with {@code closed}, the year's decided ones). */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> queue(boolean closed) {
		if (!canVerify()) {
			return List.of();
		}
		UUID me = currentUser.requireEmployeeId();
		return jdbc.sql(SQL + " where t.org_id = :org and t.employee_id <> :me and " + (closed ? "t.status in ('VERIFIED', 'REJECTED') order by t.decided_at desc limit 100"
				: "t.status = 'SUBMITTED' order by t.submitted_at")).param("org", currentUser.orgId()).param("me", me).query((rs, i) -> view(rs)).list();
	}

	@Transactional(readOnly = true)
	public long pendingCount() {
		if (!canVerify()) {
			return 0;
		}
		return jdbc.sql("select count(*) from tax_declaration where org_id = :org and status = 'SUBMITTED' and employee_id <> :me").param("org", currentUser.orgId())
				.param("me", currentUser.requireEmployeeId()).query(Long.class).single();
	}

	@Transactional
	public Map<String, Object> decide(UUID id, boolean verify, String note) {
		if (!canVerify()) {
			throw new ForbiddenException("Only HR or payroll can verify tax declarations");
		}
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> d = jdbc.sql(SQL + " where t.id = :id and t.org_id = :org").param("id", id).param("org", currentUser.orgId()).query((rs, i) -> view(rs))
				.optional().orElseThrow(() -> new NotFoundException("Declaration not found"));
		@SuppressWarnings("unchecked")
		UUID emp = (UUID) ((Map<String, Object>) d.get("employee")).get("id");
		if (emp.equals(me)) {
			throw new ForbiddenException("You can’t verify your own declaration");
		}
		if (!"SUBMITTED".equals(d.get("status"))) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NOT_SUBMITTED", "This declaration isn’t waiting for verification")));
		}
		String n = note == null || note.isBlank() ? null : note.trim();
		jdbc.sql("update tax_declaration set status = :s, decided_by = :me, decided_at = now(), decision_note = :n, updated_at = now(), version = version + 1 where id = :id")
				.param("s", verify ? "VERIFIED" : "REJECTED").param("me", me).param("n", n, java.sql.Types.VARCHAR).param("id", id).update();
		notifier.inApp(List.of(emp), "REQUEST", verify ? "HR verified your tax declaration" : "HR sent back your tax declaration",
				verify ? d.get("fy") + " · TDS updates from the next payroll" : d.get("fy") + (n == null ? " · please review" : " · " + n), "/me/requests?tab=tax");
		audit.publish("requests", "TaxDeclaration", id, verify ? "VERIFIED" : "REJECTED", null, Map.of("detail", String.valueOf(d.get("fy"))));
		return jdbc.sql(SQL + " where t.id = :id").param("id", id).query((rs, i) -> view(rs)).single();
	}
}
