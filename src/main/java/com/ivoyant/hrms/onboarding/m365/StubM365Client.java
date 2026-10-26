package com.ivoyant.hrms.onboarding.m365;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in until the Graph tenant is consented: succeeds after a short delay so the four steps are visible. */
@Component
class StubM365Client implements M365Client {

	private static final Logger log = LoggerFactory.getLogger(StubM365Client.class);

	private static void pause() {
		try {
			Thread.sleep(650);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean upnFree(String upn) {
		return true;
	}

	@Override
	public String createUser(String upn, String displayName, String password, boolean forceReset, boolean enabled) {
		pause();
		log.info("[m365 stub] create user {} (enabled={}, forceReset={})", upn, enabled, forceReset);
		return "stub-" + UUID.randomUUID();
	}

	@Override
	public void assignLicense(String userId, String licenseSku) {
		pause();
		log.info("[m365 stub] assign licence {} to {}", licenseSku, userId);
	}

	@Override
	public void addToGroups(String userId, List<String> groups) {
		pause();
		log.info("[m365 stub] add {} to groups {}", userId, groups);
	}

	@Override
	public void awaitMailbox(String userId) {
		pause();
		log.info("[m365 stub] mailbox ready for {}", userId);
	}

	@Override
	public void resetPassword(String userId, String password, boolean forceReset) {
		log.info("[m365 stub] reset password of {}", userId);
	}

	@Override
	public void disable(String userId) {
		log.info("[m365 stub] disable {}", userId);
	}

	@Override
	public void enable(String userId) {
		log.info("[m365 stub] enable {}", userId);
	}

	@Override
	public boolean simulated() {
		return true;
	}
}
