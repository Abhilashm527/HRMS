package com.ivoyant.hrms.employee.job;

import com.ivoyant.hrms.employee.service.JobChangeService;
import com.ivoyant.hrms.employee.service.LifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily employee jobs: scheduled job changes, probation reminders, exits on the last working day. */
@Component
class EmployeeJobs {

	private static final Logger log = LoggerFactory.getLogger(EmployeeJobs.class);

	private final JobChangeService jobChanges;
	private final LifecycleService lifecycle;

	EmployeeJobs(JobChangeService jobChanges, LifecycleService lifecycle) {
		this.jobChanges = jobChanges;
		this.lifecycle = lifecycle;
	}

	@Scheduled(cron = "0 30 0 * * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void jobChanges() {
		log.info("job changes applied: {}", jobChanges.applyDue());
	}

	@Scheduled(cron = "0 0 9 * * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void probationReminders() {
		log.info("probation reminders: {}", lifecycle.remindProbation());
	}

	@Scheduled(cron = "0 30 23 * * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void exits() {
		log.info("exits completed: {}", lifecycle.completeDue());
	}
}
