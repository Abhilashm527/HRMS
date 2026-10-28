package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Exit: seats are taken back on the last working day (when the exit completes); hardware stays with the person until IT recovers it. Plus the monthly usage snapshot. */
@Component
class AssetListeners {

	private static final Logger log = LoggerFactory.getLogger(AssetListeners.class);

	private final LicenseService licenses;

	AssetListeners(LicenseService licenses) {
		this.licenses = licenses;
	}

	@EventListener
	void onExit(EmployeeExitedEvent event) {
		int n = licenses.reclaimSeats(event.employeeId());
		if (n > 0) {
			log.info("{} licence seats reclaimed from {}", n, event.employeeId());
		}
	}

	@Scheduled(cron = "0 15 1 1 * *", zone = "${hrms.onboarding.zone:Asia/Kolkata}")
	void monthlySnapshot() {
		log.info("licence usage snapshot rows: {}", licenses.snapshot());
	}
}
