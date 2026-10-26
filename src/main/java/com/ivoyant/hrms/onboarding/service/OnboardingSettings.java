package com.ivoyant.hrms.onboarding.service;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment settings of the onboarding module (no secrets in files: the data key comes from the environment). */
@Component
public class OnboardingSettings {

	private final String portalUrl;
	private final String dataKey;
	private final Path storageDir;

	OnboardingSettings(@Value("${hrms.onboarding.portal-url:http://localhost:3000/onboarding}") String portalUrl,
			@Value("${hrms.onboarding.data-key:}") String dataKey,
			@Value("${hrms.onboarding.storage-dir:./data/onboarding}") String storageDir) {
		this.portalUrl = portalUrl;
		this.dataKey = dataKey == null || dataKey.isBlank() ? "dev-only-data-key-set-HRMS_ONBOARDING_DATA_KEY" : dataKey;
		this.storageDir = Path.of(storageDir);
	}

	public String portalUrl() {
		return portalUrl;
	}

	String dataKey() {
		return dataKey;
	}

	public Path storageDir() {
		return storageDir;
	}
}
