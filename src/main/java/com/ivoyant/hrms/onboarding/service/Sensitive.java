package com.ivoyant.hrms.onboarding.service;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Encryption of account, Aadhaar and PAN values (pgcrypto), their masks, and the portal format rules. */
@Component
public class Sensitive {

	public static final Set<String> KEYS = Set.of("acct", "aadhaar", "pan");

	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");
	private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
	private static final Pattern AADHAAR = Pattern.compile("^\\d{12}$");
	private static final Pattern ACCOUNT = Pattern.compile("^\\d{9,18}$");

	private final JdbcClient jdbc;
	private final OnboardingSettings settings;

	Sensitive(JdbcClient jdbc, OnboardingSettings settings) {
		this.jdbc = jdbc;
		this.settings = settings;
	}

	public static boolean is(String key) {
		return KEYS.contains(key);
	}

	public byte[] encrypt(String plain) {
		return jdbc.sql("select pgp_sym_encrypt(:v, :k)").param("v", plain).param("k", settings.dataKey()).query(byte[].class).single();
	}

	public String decrypt(byte[] enc) {
		return enc == null ? null : jdbc.sql("select pgp_sym_decrypt(:v, :k)").param("v", enc).param("k", settings.dataKey()).query(String.class).single();
	}

	/** Upper-cases PAN/IFSC and strips spaces/dashes from the numeric ones. */
	public static String normalize(String key, String raw) {
		if (raw == null) {
			return null;
		}
		String v = raw.trim();
		return switch (key) {
			case "pan", "ifsc" -> v.replace(" ", "").toUpperCase();
			case "aadhaar", "acct" -> v.replaceAll("[\\s-]", "");
			default -> v;
		};
	}

	/** An error message when the (normalized) value breaks the format rule of the key, else null. */
	public static String invalid(String key, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return switch (key) {
			case "pan" -> PAN.matcher(value).matches() ? null : "PAN looks like ABCDE1234F";
			case "ifsc" -> IFSC.matcher(value).matches() ? null : "IFSC looks like HDFC0001234";
			case "aadhaar" -> AADHAAR.matcher(value).matches() ? null : "Aadhaar is 12 digits";
			case "acct" -> ACCOUNT.matcher(value).matches() ? null : "Account number is 9 to 18 digits";
			default -> null;
		};
	}

	public static String mask(String key, String value) {
		if (value == null || value.isBlank()) {
			return value;
		}
		int n = value.length();
		return switch (key) {
			case "pan" -> n >= 5 ? value.substring(0, 2) + "•".repeat(n - 5) + value.substring(n - 3) : "•".repeat(n);
			case "aadhaar" -> "•••• •••• " + value.substring(Math.max(0, n - 4));
			case "acct" -> "•••• " + value.substring(Math.max(0, n - 4));
			default -> value;
		};
	}
}
