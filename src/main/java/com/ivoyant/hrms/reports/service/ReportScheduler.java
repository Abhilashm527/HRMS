package com.ivoyant.hrms.reports.service;

import com.ivoyant.hrms.common.security.AppPrincipal;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.reports.service.ReportCatalog.Def;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

/**
 * Runs due report schedules once a minute. Each run happens as the person who set it up, so it only ever contains what they can see;
 * restricted reports are always masked; a recipient who is an employee without access to the report is skipped.
 */
@Component
class ReportScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

	private final JdbcClient jdbc;
	private final ReportService reports;
	private final Notifier notifier;
	private final TransactionTemplate tx;

	ReportScheduler(JdbcClient jdbc, ReportService reports, Notifier notifier, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.reports = reports;
		this.notifier = notifier;
		this.tx = tx;
	}

	private record Due(UUID id, UUID org, String key, String freq, String day, String wd, LocalTime time, String to, String fmt, UUID by) {
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
	void tick() {
		List<UUID> ids = jdbc.sql("select id from report_schedule where enabled and next_run_at <= now() order by next_run_at limit 10").query(UUID.class).list();
		for (UUID id : ids) {
			try {
				runOne(id);
			}
			catch (RuntimeException ex) {
				log.warn("Report schedule {} failed: {}", id, ex.getMessage());
			}
			finally {
				SecurityContextHolder.clearContext();
			}
		}
	}

	private void runOne(UUID id) {
		// claim the run first (moving next_run_at on), so two app instances never send it twice
		Due x = tx.execute(st -> {
			Due d = jdbc.sql("""
					select id, org_id, report_key, frequency, day_of_month, weekday, at_time, recipients, format, created_by from report_schedule
					where id = :id and enabled and next_run_at <= now() for update skip locked""").param("id", id)
					.query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
							rs.getObject(7, LocalTime.class), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class)))
					.optional().orElse(null);
			if (d != null) {
				jdbc.sql("update report_schedule set next_run_at = :n where id = :id")
						.param("n", ReportService.nextRun(d.freq(), d.day(), d.wd(), d.time(), OffsetDateTime.now(ReportService.IST))).param("id", id).update();
			}
			return d;
		});
		if (x == null) {
			return;
		}
		as(x.by(), x.org());
		try {
			String note = tx.execute(st -> deliver(x));
			jdbc.sql("update report_schedule set last_run_at = now(), last_status = 'DELIVERED', last_error = :e where id = :id").param("e", note, java.sql.Types.VARCHAR)
					.param("id", x.id()).update();
		}
		catch (RuntimeException ex) {
			String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			msg = msg.length() > 300 ? msg.substring(0, 300) : msg;
			log.warn("Report schedule {} failed: {}", x.id(), msg);
			jdbc.sql("""
					insert into report_export (org_id, report_key, params, format, run_by, schedule_id, status, error)
					values (:org, :k, 'Scheduled run', :f, :by, :s, 'FAILED', :e)""").param("org", x.org()).param("k", x.key()).param("f", x.fmt()).param("by", x.by())
					.param("s", x.id()).param("e", msg).update();
			jdbc.sql("update report_schedule set last_run_at = now(), last_status = 'FAILED', last_error = :e where id = :id").param("e", msg).param("id", x.id()).update();
		}
	}

	/** Builds the file and queues the email; returns a note when some recipients were skipped. */
	private String deliver(Due x) {
		Def d = ReportCatalog.BY_KEY.get(x.key());
		if (d == null || !reports.canRun(d)) {
			throw new IllegalStateException("The person who set this up can no longer run this report");
		}
		List<String> send = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (String mail : x.to().split("\\s*,\\s*")) {
			UUID emp = jdbc.sql("select id from employee where org_id = :org and lower(work_email) = lower(:m) and status <> 'EXITED'").param("org", x.org())
					.param("m", mail).query(UUID.class).optional().orElse(null);
			if (emp != null) {
				as(emp, x.org());
				boolean ok = reports.canRun(d);
				as(x.by(), x.org());
				if (!ok) {
					skipped.add(mail);
					continue;
				}
			}
			send.add(mail);
		}
		if (send.isEmpty()) {
			throw new IllegalStateException("No recipient has access to this report");
		}
		Map<String, Object> out = reports.doExport(d, reports.scheduledParams(d, x.freq()), x.fmt(), List.of(), true, x.id());
		String html = "<p>Your scheduled report <b>" + HtmlUtils.htmlEscape(d.name()) + "</b> is attached.</p><p>" + HtmlUtils.htmlEscape((String) out.get("params")) + " · "
				+ out.get("rows") + " rows</p><p style=\"color:#64748b;font-size:12px\">Sent by Ivoyant HRMS. Restricted fields are masked in scheduled reports.</p>";
		notifier.email(String.join(", ", send), null, d.name() + " · " + out.get("params"), html, (String) out.get("fileKey"), (String) out.get("name"));
		return skipped.isEmpty() ? null : "Not sent to " + String.join(", ", skipped) + " (no access)";
	}

	private static void as(UUID employee, UUID org) {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new AppPrincipal(employee, org), null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))));
	}
}
