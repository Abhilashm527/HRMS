package com.ivoyant.hrms.iam.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Deployment settings of sign-in. Secrets come from the environment, never from a file. */
@Component
public class AuthProps {

	private final String issuer;
	private final String appBaseUrl;
	private final String keyEnc;
	private final String entraTenant;
	private final String entraClient;
	private final String entraSecret;
	private final String supportEmail;
	private final boolean local;

	AuthProps(@Value("${hrms.auth.issuer:http://localhost:8081}") String issuer, @Value("${hrms.auth.app-base-url:http://localhost:3000}") String appBaseUrl,
			@Value("${hrms.auth.key-enc:}") String keyEnc, @Value("${hrms.auth.entra.tenant-id:}") String entraTenant, @Value("${hrms.auth.entra.client-id:}") String entraClient,
			@Value("${hrms.auth.entra.client-secret:}") String entraSecret, @Value("${hrms.auth.support-email:it-support@ivoyant.com}") String supportEmail, Environment env) {
		this.issuer = issuer;
		this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
		this.keyEnc = keyEnc;
		this.entraTenant = entraTenant;
		this.entraClient = entraClient;
		this.entraSecret = entraSecret;
		this.supportEmail = supportEmail;
		this.local = env.acceptsProfiles(Profiles.of("local"));
		if (env.acceptsProfiles(Profiles.of("prod")) && local) {
			throw new IllegalStateException("The local profile (dev header sign-in) must not run together with prod");
		}
		if (env.acceptsProfiles(Profiles.of("prod")) && keyEnc.isBlank()) {
			throw new IllegalStateException("AUTH_KEY_ENC must be set in production");
		}
	}

	public String issuer() {
		return issuer;
	}

	public String appBaseUrl() {
		return appBaseUrl;
	}

	String keyEnc() {
		return keyEnc.isBlank() ? "dev-only-auth-key-set-AUTH_KEY_ENC" : keyEnc;
	}

	String entraTenant() {
		return entraTenant;
	}

	String entraClient() {
		return entraClient;
	}

	String entraSecret() {
		return entraSecret;
	}

	public String supportEmail() {
		return supportEmail;
	}

	boolean ssoEnabled() {
		return !entraTenant.isBlank() && !entraClient.isBlank() && !entraSecret.isBlank();
	}

	/** Cookies are marked Secure everywhere except the local profile. */
	boolean secureCookies() {
		return !local;
	}

	boolean local() {
		return local;
	}
}
