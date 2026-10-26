package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Work items for the background worker (Microsoft 365 provisioning, join, reminders). */
@Component
public class JobQueue {

	public record Job(long id, UUID orgId, String kind, UUID hireId, int attempts) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	JobQueue(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	public void enqueue(String kind, UUID hireId, OffsetDateTime runAfter) {
		jdbc.sql("insert into onboarding_job_queue (org_id, kind, hire_id, run_after) values (:org, :k, :h, :r)")
				.param("org", currentUser.orgId()).param("k", kind).param("h", hireId)
				.param("r", runAfter == null ? OffsetDateTime.now() : runAfter).update();
	}

	/** Claims due jobs: marks them locked for two minutes so another worker skips them. */
	public List<Job> claim(int max) {
		return jdbc.sql("""
				update onboarding_job_queue set locked_until = now() + interval '2 minutes'
				where id in (select id from onboarding_job_queue where done_at is null and run_after <= now()
				             and (locked_until is null or locked_until < now()) order by run_after limit :n for update skip locked)
				returning id, org_id, kind, hire_id, attempts""").param("n", max)
				.query((rs, i) -> new Job(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class), rs.getInt(5))).list();
	}

	public void done(long id) {
		jdbc.sql("update onboarding_job_queue set done_at = now(), locked_until = null where id = :id").param("id", id).update();
	}

	public void retry(long id, String error, long delaySeconds) {
		jdbc.sql("update onboarding_job_queue set attempts = attempts + 1, last_error = :e, locked_until = null, run_after = now() + make_interval(secs => :d) where id = :id")
				.param("e", error).param("d", delaySeconds).param("id", id).update();
	}

	public void fail(long id, String error) {
		jdbc.sql("update onboarding_job_queue set attempts = attempts + 1, last_error = :e, done_at = now(), locked_until = null where id = :id")
				.param("e", error).param("id", id).update();
	}
}
