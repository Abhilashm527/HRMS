package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.org.EmployeeCodes;
import com.ivoyant.hrms.org.OrgProfile;
import com.ivoyant.hrms.org.OrgSettings;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee code generator. Tokens: {@code {PREFIX}} the organization's prefix, {@code {YYYY}} the joining year (the
 * counter then restarts each year), {@code {SEQn}} a counter padded to n digits (3–6), {@code {SEQNUM}} an unpadded one.
 */
@Service
class EmployeeCodeService implements EmployeeCodes {

	private static final Pattern SEQ = Pattern.compile("\\{SEQ(\\d)\\}|\\{SEQNUM\\}");
	private static final Pattern TOKEN = Pattern.compile("\\{[A-Z0-9]+\\}");
	private static final Pattern OUTSIDE = Pattern.compile("^[A-Z0-9\\-_/]*$");
	private static final int MAX_TRIES = 50;

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgSettings settings;
	private final OrgProfile profile;

	EmployeeCodeService(JdbcClient jdbc, CurrentUser currentUser, OrgSettings settings, OrgProfile profile) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.settings = settings;
		this.profile = profile;
	}

	@Override
	@Transactional
	public String next(LocalDate joiningDate) {
		UUID org = currentUser.orgId();
		String pattern = settings.idFormat();
		LocalDate date = joiningDate != null ? joiningDate : LocalDate.now();
		String scope = scope(pattern, date);
		long seq = reserve(org, scope);
		for (int i = 0; i < MAX_TRIES; i++) {
			String candidate = format(pattern, profile.current().employeeIdPrefix(), date.getYear(), seq);
			boolean taken = jdbc.sql("select count(*) from employee where org_id = :org and upper(employee_code) = upper(:c)")
					.param("org", org).param("c", candidate).query(Long.class).single() > 0;
			if (!taken) {
				jdbc.sql("update employee_code_seq set next_val = :next where org_id = :org and scope = :scope")
						.param("next", seq + 1).param("org", org).param("scope", scope).update();
				return candidate;
			}
			seq++;
		}
		throw new ConflictException("Could not find a free employee code; check the employee ID pattern");
	}

	@Override
	@Transactional(readOnly = true)
	public String preview(String pattern) {
		String problem = validate(pattern);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		UUID org = currentUser.orgId();
		LocalDate today = LocalDate.now();
		long seq = peek(org, scope(pattern, today));
		for (int i = 0; i < MAX_TRIES; i++) {
			String candidate = format(pattern, profile.current().employeeIdPrefix(), today.getYear(), seq);
			boolean taken = jdbc.sql("select count(*) from employee where org_id = :org and upper(employee_code) = upper(:c)")
					.param("org", org).param("c", candidate).query(Long.class).single() > 0;
			if (!taken) {
				return candidate;
			}
			seq++;
		}
		return format(pattern, profile.current().employeeIdPrefix(), today.getYear(), seq);
	}

	/** The pattern's problem in words, or null when it is fine. */
	static String validate(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return "The pattern cannot be empty";
		}
		if (pattern.length() > 40) {
			return "The pattern can be at most 40 characters";
		}
		Matcher seq = SEQ.matcher(pattern);
		int count = 0;
		while (seq.find()) {
			count++;
			if (seq.group(1) != null && (Integer.parseInt(seq.group(1)) < 3 || Integer.parseInt(seq.group(1)) > 6)) {
				return "{SEQn} needs n between 3 and 6";
			}
		}
		if (count != 1) {
			return "The pattern needs exactly one counter such as {SEQ4}";
		}
		Matcher tokens = TOKEN.matcher(pattern);
		while (tokens.find()) {
			String t = tokens.group();
			if (!t.equals("{PREFIX}") && !t.equals("{YYYY}") && !t.equals("{SEQNUM}") && !SEQ.matcher(t).matches()) {
				return "Unknown token " + t + ". Use {PREFIX}, {YYYY} and {SEQ4}";
			}
		}
		String outside = TOKEN.matcher(pattern).replaceAll("");
		if (!OUTSIDE.matcher(outside).matches()) {
			return "Outside the tokens use only A–Z, 0–9, - _ and /";
		}
		return null;
	}

	static String format(String pattern, String prefix, int year, long seq) {
		String replaced = pattern.replace("{PREFIX}", prefix).replace("{YYYY}", String.valueOf(year));
		Matcher seqMatch = SEQ.matcher(replaced);
		if (!seqMatch.find()) {
			return replaced;
		}
		String number = seqMatch.group(1) == null ? String.valueOf(seq)
				: String.format("%0" + seqMatch.group(1) + "d", seq);
		return replaced.substring(0, seqMatch.start()) + number + replaced.substring(seqMatch.end());
	}

	private static String scope(String pattern, LocalDate date) {
		return pattern.contains("{YYYY}") ? String.valueOf(date.getYear()) : "ALL";
	}

	/** The counter for the scope, created on first use; the row stays locked until the transaction ends. */
	private long reserve(UUID org, String scope) {
		jdbc.sql("insert into employee_code_seq (org_id, scope, next_val) values (:org, :scope, :start) on conflict do nothing")
				.param("org", org).param("scope", scope).param("start", start(scope)).update();
		return jdbc.sql("select next_val from employee_code_seq where org_id = :org and scope = :scope for update")
				.param("org", org).param("scope", scope).query(Long.class).single();
	}

	/** The counter as it stands, without creating or locking anything (for previews). */
	private long peek(UUID org, String scope) {
		return jdbc.sql("select next_val from employee_code_seq where org_id = :org and scope = :scope")
				.param("org", org).param("scope", scope).query(Long.class).optional().orElse(start(scope));
	}

	private static long start(String scope) {
		return "ALL".equals(scope) ? 1001L : 1L;
	}
}
