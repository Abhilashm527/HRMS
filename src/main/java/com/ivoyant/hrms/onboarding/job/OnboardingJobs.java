package com.ivoyant.hrms.onboarding.job;

import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.service.HireJoinService;
import com.ivoyant.hrms.onboarding.service.HireStatusService;
import com.ivoyant.hrms.onboarding.service.InviteService;
import com.ivoyant.hrms.onboarding.service.JobQueue;
import com.ivoyant.hrms.onboarding.service.M365Service;
import com.ivoyant.hrms.org.OrgProfile;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background work: the provisioning queue, the daily join, reminders and the nightly status refresh. */
@Component
class OnboardingJobs {

	private static final Logger log = LoggerFactory.getLogger(OnboardingJobs.class);

	private final JdbcClient jdbc;
	private final JobQueue queue;
	private final M365Service m365;
	private final HireJoinService join;
	private final InviteService invites;
	private final HireStatusService status;
	private final OrgProfile orgProfile;
	private final Notifier notifier;

	OnboardingJobs(JdbcClient jdbc, JobQueue queue, M365Service m365, HireJoinService join, InviteService invites, HireStatusService status,
			OrgProfile orgProfile, Notifier notifier) {
		this.jdbc = jdbc;
		this.queue = queue;
		this.m365 = m365;
		this.join = join;
		this.invites = invites;
		this.status = status;
		this.orgProfile = orgProfile;
		this.notifier = notifier;
	}

	/** Picks up queued work every two seconds (Microsoft 365 provisioning is the common item). */
	@Scheduled(fixedDelay = 2000, initialDelay = 15000)
	void work() {
		List<JobQueue.Job> jobs;
		try {
			jobs = queue.claim(5);
		}
		catch (RuntimeException ex) {
			log.warn("onboarding queue not readable: {}", ex.getMessage());
			return;
		}
		for (JobQueue.Job job : jobs) {
			try {
				switch (job.kind()) {
					case "M365_PROVISION" -> m365.run(job.hireId(), job.attempts());
					case "JOIN" -> join.join(job.hireId(), false, null);
					case "REMIND" -> invites.autoRemind(job.hireId());
					default -> log.warn("unknown onboarding job {}", job.kind());
				}
				queue.done(job.id());
			}
			catch (RuntimeException ex) {
				log.warn("onboarding job {} {} failed: {}", job.id(), job.kind(), ex.getMessage());
				queue.fail(job.id(), String.valueOf(ex.getMessage()));
			}
		}
	}

	/** 06:00 every day: joins hires whose day has come and who are ready; tells HR about the ones that are not. */
	@Scheduled(cron = "0 0 6 * * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void joinDue() {
		LocalDate today = status.today();
		List<UUID> ready = jdbc.sql("select id from onboarding_hire where doj <= :d and status = 'READY_TO_JOIN' and joined_at is null and cancelled_at is null")
				.param("d", today).query(UUID.class).list();
		for (UUID id : ready) {
			try {
				join.join(id, false, null);
			}
			catch (RuntimeException ex) {
				log.warn("could not join hire {}: {}", id, ex.getMessage());
			}
		}
		long late = jdbc.sql("select count(*) from onboarding_hire where doj <= :d and status <> 'READY_TO_JOIN' and status <> 'JOINED' and joined_at is null and cancelled_at is null")
				.param("d", today).query(Long.class).single();
		if (late > 0) {
			Set<UUID> to = hrOwners();
			notifier.inApp(to, late + (late == 1 ? " hire is" : " hires are") + " past the joining date and not ready", "Open the onboarding pipeline to finish verification.", "/onboarding");
		}
	}

	private Set<UUID> hrOwners() {
		return Set.copyOf(jdbc.sql("""
				select u.employee_id from area_owner o join app_user u on u.id = o.user_id where o.area_key = 'onboarding'
				union select u.employee_id from area_owner_role r join user_role ur on ur.role_id = r.role_id join app_user u on u.id = ur.user_id where r.area_key = 'onboarding'""")
				.query(UUID.class).list());
	}

	/** Hourly, 09:00–19:00 in the organization's time zone: automatic reminders. */
	@Scheduled(cron = "0 5 * * * *")
	void remind() {
		int hour = ZonedDateTime.now(orgProfile.zone()).getHour();
		if (hour < 9 || hour >= 19) {
			return;
		}
		List<UUID> due = jdbc.sql("""
				select i.hire_id from onboarding_invite i join onboarding_hire h on h.id = i.hire_id
				where i.revoked_at is null and i.remind_every_days is not null and h.joined_at is null and h.cancelled_at is null
				  and h.doj >= :today and h.status in ('INVITED', 'IN_PROGRESS', 'ACTION_NEEDED')
				  and now() >= coalesce(i.last_reminder_at, i.first_sent_at) + make_interval(days => i.remind_every_days)""")
				.param("today", status.today()).query(UUID.class).list();
		for (UUID id : due) {
			try {
				invites.autoRemind(id);
			}
			catch (RuntimeException ex) {
				log.warn("reminder for hire {} failed: {}", id, ex.getMessage());
			}
		}
	}

	/** Shortly after midnight: refreshes the date-based stage of every open hire and switches on accounts that start tomorrow. */
	@Scheduled(cron = "0 5 0 * * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void refresh() {
		for (UUID id : jdbc.sql("select id from onboarding_hire where joined_at is null and cancelled_at is null").query(UUID.class).list()) {
			try {
				status.recompute(id);
			}
			catch (RuntimeException ex) {
				log.warn("status refresh of hire {} failed: {}", id, ex.getMessage());
			}
		}
		try {
			m365.enableDue();
		}
		catch (RuntimeException ex) {
			log.warn("enabling accounts failed: {}", ex.getMessage());
		}
	}
}
