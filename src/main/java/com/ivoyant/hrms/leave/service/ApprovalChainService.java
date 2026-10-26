package com.ivoyant.hrms.leave.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.LeavePolicyChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.leave.ResolvedStep;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Leave &amp; attendance → Approval chain: one chain per organization, saved whole. Requests resolve the chain when they are
 * submitted and keep that result, so editing the chain never changes a request already in flight.
 */
@Service
public class ApprovalChainService {

	private static final Set<String> APPROVERS = Set.of("REPORTING_MANAGER", "SKIP_LEVEL_MANAGER", "DEPARTMENT_HEAD", "HR");

	public record StepView(String approver, String label, String condition, BigDecimal minDays) {
	}

	public record Chain(long version, List<StepView> steps) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final LeaveChainResolver resolver;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public ApprovalChainService(JdbcClient jdbc, CurrentUser currentUser, LeaveChainResolver resolver, Audit audit, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.resolver = resolver;
		this.audit = audit;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public Chain get() {
		long version = jdbc.sql("select version from leave_chain_version where org_id = :org").param("org", currentUser.orgId()).query(Long.class).optional().orElse(0L);
		return new Chain(version, steps().stream().map(ApprovalChainService::view).toList());
	}

	List<LeaveChainResolver.Step> steps() {
		return jdbc.sql("select approver, condition, min_days from leave_approval_step where org_id = :org order by sort").param("org", currentUser.orgId())
				.query((rs, i) -> new LeaveChainResolver.Step(rs.getString(1), rs.getString(2), rs.getBigDecimal(3))).list();
	}

	/** {@code {steps: [{approver, condition, minDays}]}}: the whole ordered chain, with {@code If-Match: <version>}. */
	@Transactional
	public Chain put(Map<String, Object> body, String ifMatch) {
		long version = get().version();
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(version))) {
			throw new StaleException("The approval chain was changed by someone else. Reload and try again.");
		}
		List<LeaveChainResolver.Step> next = parse(body.get("steps"));
		Chain before = get();
		jdbc.sql("delete from leave_approval_step where org_id = :org").param("org", currentUser.orgId()).update();
		int sort = 1;
		for (LeaveChainResolver.Step s : next) {
			jdbc.sql("insert into leave_approval_step (org_id, sort, approver, condition, min_days) values (:org, :s, :a, :c, :m)")
					.param("org", currentUser.orgId()).param("s", sort++).param("a", s.approver()).param("c", s.condition())
					.param("m", s.minDays(), java.sql.Types.NUMERIC).update();
		}
		jdbc.sql("""
				insert into leave_chain_version (org_id, version, updated_by) values (:org, 1, :by)
				on conflict (org_id) do update set version = leave_chain_version.version + 1, updated_at = now(), updated_by = :by""")
				.param("org", currentUser.orgId()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		Chain after = get();
		audit.updated("leave", "ApprovalChain", currentUser.orgId(), describe(before), describe(after));
		events.publishEvent(new LeavePolicyChangedEvent(null));
		return after;
	}

	/** {@code {employeeId, days, steps?}}: who would approve; {@code steps} (optional) previews an unsaved draft. */
	@Transactional(readOnly = true)
	public List<ResolvedStep> preview(Map<String, Object> body) {
		UUID employee = parseUuid(body.get("employeeId"));
		BigDecimal days = body.get("days") == null ? BigDecimal.ONE : new BigDecimal(body.get("days").toString());
		List<LeaveChainResolver.Step> steps = body.get("steps") == null ? steps() : parse(body.get("steps"));
		return resolver.resolve(employee, days, steps);
	}

	List<ResolvedStep> chainFor(UUID employee, BigDecimal days) {
		return resolver.resolve(employee, days, steps());
	}

	// ---- rules ----

	@SuppressWarnings("unchecked")
	private List<LeaveChainResolver.Step> parse(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw invalid("steps", "LAST_STEP", "A chain needs at least one step");
		}
		List<LeaveChainResolver.Step> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < list.size(); i++) {
			Map<String, Object> row = (Map<String, Object>) list.get(i);
			String approver = approver(row.get("approver"));
			if (!APPROVERS.contains(approver)) {
				throw invalid("steps[" + i + "].approver", "INVALID", "Pick the reporting manager, skip-level manager, department head or HR");
			}
			String text = row.get("condition") == null ? "ALWAYS" : row.get("condition").toString().trim().toUpperCase(Locale.ROOT);
			boolean over = text.startsWith("OVER");
			BigDecimal min = null;
			if (over) {
				Object m = row.get("minDays");
				if (m == null && text.matches("OVER\\s+\\d+.*")) {
					m = text.replaceAll("[^0-9.]", "");
				}
				try {
					min = m == null ? null : new BigDecimal(m.toString()).setScale(1, RoundingMode.HALF_UP);
				}
				catch (NumberFormatException ex) {
					min = null;
				}
				if (min == null || min.compareTo(new BigDecimal("0.5")) < 0 || min.compareTo(new BigDecimal("365")) > 0) {
					throw invalid("steps[" + i + "].minDays", "INVALID", "Set a number of days between 0.5 and 365");
				}
			}
			LeaveChainResolver.Step step = new LeaveChainResolver.Step(approver, over ? "OVER_DAYS" : "ALWAYS", min);
			if (!seen.add(step.approver() + "|" + step.condition() + "|" + step.minDays())) {
				throw invalid("steps[" + i + "]", "DUPLICATE_STEP", "That step is already in the chain");
			}
			out.add(step);
		}
		return out;
	}

	private static String approver(Object v) {
		String t = v == null ? "" : v.toString().trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
		return switch (t) {
			case "REPORTING_MANAGER", "MANAGER" -> "REPORTING_MANAGER";
			case "SKIP_LEVEL_MANAGER", "SKIP_LEVEL" -> "SKIP_LEVEL_MANAGER";
			case "DEPARTMENT_HEAD" -> "DEPARTMENT_HEAD";
			case "HR" -> "HR";
			default -> t;
		};
	}

	private static StepView view(LeaveChainResolver.Step s) {
		String label = switch (s.approver()) {
			case "REPORTING_MANAGER" -> "Reporting manager";
			case "SKIP_LEVEL_MANAGER" -> "Skip-level manager";
			case "DEPARTMENT_HEAD" -> "Department head";
			default -> "HR";
		};
		return new StepView(s.approver(), label, s.condition(), s.minDays());
	}

	private static StepView view(StepView s) {
		return s;
	}

	private static Map<String, Object> describe(Chain c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("steps", c.steps().stream().map(s -> s.label() + ("OVER_DAYS".equals(s.condition()) ? " · over " + s.minDays() + " days" : " · always")).toList());
		return m;
	}

	private static UUID parseUuid(Object v) {
		try {
			return UUID.fromString(String.valueOf(v));
		}
		catch (IllegalArgumentException ex) {
			throw invalid("employeeId", "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
