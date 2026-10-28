package com.ivoyant.hrms.iam.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.config.HrmsProperties;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.notification.Notifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Sign-in with email and password, the two-step check, refresh, sign out, reset, change, activation and the session list. Microsoft
 * sign-in ends in the same session code ({@link SsoService}).
 */
@Service
public class AuthService {

	private static final int MAX_FAILS = 5;
	private static final Duration LOCK = Duration.ofMinutes(15);
	private static final Duration CHALLENGE = Duration.ofMinutes(5);
	private static final int MFA_TRIES = 5;

	/** What the caller needs to answer a request: the OK body, the refresh cookie, or a pending step. */
	public record Outcome(Map<String, Object> body, TokenService.Issued issued) {
	}

	record Account(UUID userId, UUID employeeId, UUID orgId, String email, String name, String firstName, String status, boolean breakGlass, String employeeStatus, String passwordHash,
			int failed, OffsetDateTime lockedUntil, byte[] totpSecret, boolean mfaResetRequired, UUID externalSubject) {
	}

	private final JdbcClient jdbc;
	private final HrmsProperties props;
	private final AuthProps authProps;
	private final PasswordService passwords;
	private final TotpService totp;
	private final TokenService tokens;
	private final AuthCrypto crypto;
	private final RateLimiter limiter;
	private final SecurityAudit audit;
	private final Notifier notifier;
	private final ObjectMapper json;
	/** Unknown emails get a made-up countdown so they look like real accounts. */
	private final Cache<String, AtomicInteger> fakeFails = Caffeine.newBuilder().expireAfterWrite(LOCK).maximumSize(20_000).build();

	AuthService(JdbcClient jdbc, HrmsProperties props, AuthProps authProps, PasswordService passwords, TotpService totp, TokenService tokens, AuthCrypto crypto, RateLimiter limiter,
			SecurityAudit audit, Notifier notifier, ObjectMapper json) {
		this.jdbc = jdbc;
		this.props = props;
		this.authProps = authProps;
		this.passwords = passwords;
		this.totp = totp;
		this.tokens = tokens;
		this.crypto = crypto;
		this.limiter = limiter;
		this.audit = audit;
		this.notifier = notifier;
		this.json = json;
	}

	// ================================================================== config

	public Map<String, Object> config() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ssoEnabled", authProps.ssoEnabled());
		m.put("passwordEnabled", passwords.passwordLoginEnabled());
		m.put("domains", domains());
		m.put("supportEmail", authProps.supportEmail());
		m.put("pwPolicy", passwords.mode());
		return m;
	}

	List<String> domains() {
		return jdbc.sql("select email_domains from org where id = :org").param("org", props.defaultOrgId()).query((rs, i) -> List.of((String[]) rs.getArray(1).getArray())).optional().orElse(List.of());
	}

	boolean twoFaRequiredByOrg() {
		return jdbc.sql("select two_fa from org_settings where org_id = :org").param("org", props.defaultOrgId()).query(Boolean.class).optional().orElse(true);
	}

	// ================================================================== lookup

	Account find(String email) {
		return jdbc.sql("""
				select u.id, u.employee_id, u.org_id, u.email, e.display_name, e.first_name, u.status, u.break_glass, e.status, c.password_hash, coalesce(c.failed_count, 0), c.locked_until,
				       c.totp_secret_enc, coalesce(c.mfa_reset_required, false)
				from app_user u join employee e on e.id = u.employee_id left join auth_credential c on c.user_id = u.id
				where u.org_id = :org and lower(u.email) = :e""").param("org", props.defaultOrgId()).param("e", email).query((rs, i) -> new Account(rs.getObject(1, UUID.class),
						rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9),
						rs.getString(10), rs.getInt(11), rs.getObject(12, OffsetDateTime.class), rs.getBytes(13) == null ? null : crypto.decrypt(rs.getBytes(13)), rs.getBoolean(14), null))
				.optional().orElse(null);
	}

	Account findById(UUID userId) {
		String email = jdbc.sql("select lower(email) from app_user where id = :id").param("id", userId).query(String.class).optional().orElse(null);
		return email == null ? null : find(email);
	}

	boolean consoleAccess(UUID userId) {
		return jdbc.sql("""
				select count(*) from user_role ur join role r on r.id = ur.role_id where ur.user_id = :u and (r.key in ('super_admin', 'hr_admin')
				  or exists (select 1 from role_permission rp where rp.role_id = r.id))""").param("u", userId).query(Long.class).single() > 0;
	}

	String landing(UUID userId) {
		return consoleAccess(userId) ? "ADMIN" : "ESS";
	}

	// ================================================================== password login

	@Transactional(noRollbackFor = StatusException.class)
	public Outcome login(String rawEmail, String password, boolean remember, String ip, String userAgent) {
		limiter.hit("login-ip:" + ip, 20, Duration.ofMinutes(15));
		String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
		String domain = email.contains("@") ? email.substring(email.indexOf('@') + 1) : "";
		List<String> allowed = domains();
		if (email.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || allowed.stream().noneMatch(d -> d.equalsIgnoreCase(domain))) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_DOMAIN", "Use your @" + (allowed.isEmpty() ? "company" : allowed.get(0)) + " work email");
		}
		Account a = find(email);
		boolean entra = !passwords.passwordLoginEnabled();
		if (entra && (a == null || !a.breakGlass())) {
			passwords.burn(password);
			throw new StatusException(HttpStatus.FORBIDDEN, "PASSWORD_LOGIN_DISABLED", "Sign in with Microsoft");
		}
		if (a == null || a.passwordHash() == null) {
			passwords.burn(password);
			throw failedUnknown(email);
		}
		if (a.lockedUntil() != null && a.lockedUntil().isAfter(OffsetDateTime.now())) {
			passwords.burn(password);
			throw locked(a.lockedUntil());
		}
		if (!passwords.matches(password == null ? "" : password, a.passwordHash())) {
			int failed = a.failed() + 1;
			OffsetDateTime until = failed >= MAX_FAILS ? OffsetDateTime.now().plus(LOCK) : null;
			jdbc.sql("update auth_credential set failed_count = :f, last_failed_at = now(), locked_until = :l where user_id = :u").param("f", failed).param("l", until).param("u", a.userId()).update();
			audit.log(failed >= MAX_FAILS ? "LOCKED" : "LOGIN_FAILED", a.employeeId(), a.userId().toString(), Map.of("email", email, "attempt", failed));
			if (until != null) {
				throw locked(until);
			}
			throw new StatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password", Map.of("attemptsLeft", MAX_FAILS - failed));
		}
		if (!"ACTIVE".equals(a.status()) || "EXITED".equals(a.employeeStatus())) {
			throw new StatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Your account is disabled. Contact IT.");
		}
		jdbc.sql("update auth_credential set failed_count = 0, locked_until = null where user_id = :u").param("u", a.userId()).update();
		boolean mfaNeeded = twoFaRequiredByOrg() || consoleAccess(a.userId());
		if (mfaNeeded) {
			if (a.totpSecret() == null || a.mfaResetRequired()) {
				return pending(Map.of("status", "MFA_ENROLL_REQUIRED", "enrollToken", token(a.userId(), "MFA_ENROLL", Duration.ofMinutes(15), Map.of("remember", remember, "method", "PASSWORD"))));
			}
			return pending(Map.of("status", "MFA_REQUIRED", "challengeId", token(a.userId(), "MFA_CHALLENGE", CHALLENGE, Map.of("remember", remember, "attempts", 0)), "methods",
					List.of("TOTP", "RECOVERY"), "expiresIn", (int) CHALLENGE.toSeconds()));
		}
		return signIn(a, "PASSWORD", List.of("pwd"), remember, ip, userAgent);
	}

	private static Outcome pending(Map<String, Object> body) {
		return new Outcome(body, null);
	}

	private StatusException failedUnknown(String email) {
		AtomicInteger n = fakeFails.get(email, k -> new AtomicInteger());
		int failed = n.incrementAndGet();
		if (failed >= MAX_FAILS) {
			return locked(OffsetDateTime.now().plus(LOCK));
		}
		return new StatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password", Map.of("attemptsLeft", MAX_FAILS - failed));
	}

	private static StatusException locked(OffsetDateTime until) {
		long seconds = Math.max(1, Duration.between(OffsetDateTime.now(), until).toSeconds());
		return new StatusException(HttpStatus.LOCKED, "LOCKED", "Account locked after 5 failed attempts. Reset your password or try again in 15 minutes.", Map.of("retryAfter", seconds));
	}

	// ================================================================== two-step

	@Transactional(noRollbackFor = StatusException.class)
	public Outcome verifyMfa(String challengeId, String code, String ip, String userAgent) {
		Map<String, Object> t = loadToken(challengeId, "MFA_CHALLENGE", "CHALLENGE_EXPIRED", "This sign-in expired. Start again.");
		UUID userId = (UUID) t.get("user");
		Account a = findById(userId);
		if (a == null || a.totpSecret() == null) {
			throw new StatusException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "This sign-in expired. Start again.");
		}
		String clean = code == null ? "" : code.trim();
		boolean ok = clean.matches("\\d{6}") ? totp.verify(userId, a.totpSecret(), clean) : totp.useRecovery(userId, clean);
		if (!ok) {
			Map<String, Object> data = dataOf(t);
			int attempts = ((Number) data.getOrDefault("attempts", 0)).intValue() + 1;
			audit.log("MFA_FAILED", a.employeeId(), userId.toString(), Map.of("attempt", attempts));
			if (attempts >= MFA_TRIES) {
				jdbc.sql("update auth_token set used_at = now() where token_hash = :h").param("h", AuthCrypto.hash(challengeId)).update();
				throw new StatusException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "Too many wrong codes. Start again.");
			}
			data.put("attempts", attempts);
			jdbc.sql("update auth_token set data = cast(:d as jsonb) where token_hash = :h").param("d", json.writeValueAsString(data)).param("h", AuthCrypto.hash(challengeId)).update();
			throw new StatusException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "That code didn't match. Use the latest code in your app.", Map.of("attemptsLeft", MFA_TRIES - attempts));
		}
		if (!clean.matches("\\d{6}")) {
			audit.log("RECOVERY_CODE_USED", a.employeeId(), userId.toString(), Map.of("left", totp.recoveryLeft(userId)));
			notifier.email(a.email(), null, "A recovery code was used on your HRMS account", "<p>A recovery code was used to sign in to your HRMS account. If this was not you, reset your password now.</p>");
		}
		jdbc.sql("update auth_token set used_at = now() where token_hash = :h").param("h", AuthCrypto.hash(challengeId)).update();
		boolean remember = Boolean.TRUE.equals(dataOf(t).get("remember"));
		return signIn(a, "PASSWORD", List.of("pwd", "otp"), remember, ip, userAgent);
	}

	// ---- enrolment (after login, activation, or from My account) ----

	public Map<String, Object> enrollStart(String enrollToken) {
		Map<String, Object> t = loadToken(enrollToken, "MFA_ENROLL", "CHALLENGE_EXPIRED", "This step expired. Start again.");
		Account a = findById((UUID) t.get("user"));
		byte[] secret = totp.newSecret();
		Map<String, Object> data = dataOf(t);
		data.put("secret", Base64.getEncoder().encodeToString(crypto.encrypt(secret)));
		jdbc.sql("update auth_token set data = cast(:d as jsonb) where token_hash = :h").param("d", json.writeValueAsString(data)).param("h", AuthCrypto.hash(enrollToken)).update();
		String url = totp.otpauth(a.email(), secret);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("secret", TotpService.grouped(TotpService.base32(secret)));
		out.put("otpauthUrl", url);
		out.put("qrSvg", totp.qrSvg(url));
		return out;
	}

	@Transactional(noRollbackFor = StatusException.class)
	public Outcome enrollConfirm(String enrollToken, String code, String ip, String userAgent) {
		Map<String, Object> t = loadToken(enrollToken, "MFA_ENROLL", "CHALLENGE_EXPIRED", "This step expired. Start again.");
		Map<String, Object> data = dataOf(t);
		if (data.get("secret") == null) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MFA_NOT_STARTED", "Scan the code first");
		}
		UUID userId = (UUID) t.get("user");
		byte[] secret = crypto.decrypt(Base64.getDecoder().decode((String) data.get("secret")));
		long step = TotpService.matchStep(secret, code == null ? "" : code.trim(), System.currentTimeMillis() / 1000);
		if (step < 0) {
			throw new StatusException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "That code didn't match. Use the latest code in your app.");
		}
		jdbc.sql("""
				insert into auth_credential (user_id, totp_secret_enc, totp_enrolled_at, totp_last_step, mfa_reset_required) values (:u, :s, now(), :st, false)
				on conflict (user_id) do update set totp_secret_enc = :s, totp_enrolled_at = now(), totp_last_step = :st, mfa_reset_required = false, version = auth_credential.version + 1""")
				.param("u", userId).param("s", crypto.encrypt(secret)).param("st", step).update();
		List<String> codes = totp.newRecoveryCodes(userId);
		jdbc.sql("update auth_token set used_at = now() where token_hash = :h").param("h", AuthCrypto.hash(enrollToken)).update();
		Account a = findById(userId);
		audit.log("MFA_ENROLLED", a.employeeId(), userId.toString(), null);
		boolean remember = Boolean.TRUE.equals(data.get("remember"));
		Outcome signed = signIn(a, String.valueOf(data.getOrDefault("method", "PASSWORD")), List.of("pwd", "otp"), remember, ip, userAgent);
		Map<String, Object> body = new LinkedHashMap<>(signed.body());
		body.put("recoveryCodes", codes);
		return new Outcome(body, signed.issued());
	}

	/** My account → Two-step verification → Reset: needs the current password; the next step is the normal enrolment. */
	public Map<String, Object> startReenroll(UUID userId, String currentPassword) {
		Account a = findById(userId);
		if (a == null || a.passwordHash() == null || !passwords.matches(currentPassword == null ? "" : currentPassword, a.passwordHash())) {
			throw new StatusException(HttpStatus.UNAUTHORIZED, "CURRENT_PASSWORD", "Current password is incorrect");
		}
		return Map.of("enrollToken", token(userId, "MFA_ENROLL", Duration.ofMinutes(15), Map.of("remember", true, "method", "PASSWORD")));
	}

	@Transactional
	public List<String> regenerateRecovery(UUID userId, String code) {
		Account a = findById(userId);
		if (a == null || a.totpSecret() == null || !totp.verify(userId, a.totpSecret(), code == null ? "" : code.trim())) {
			throw new StatusException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "That code didn't match. Use the latest code in your app.");
		}
		audit.log("MFA_ENROLLED", a.employeeId(), userId.toString(), Map.of("recoveryCodes", "regenerated"));
		return totp.newRecoveryCodes(userId);
	}

	// ================================================================== sessions

	/** Starts the session and builds the OK body. */
	@Transactional
	public Outcome signIn(Account a, String method, List<String> amr, boolean remember, String ip, String userAgent) {
		TokenService.Issued issued = tokens.start(a.userId(), a.employeeId(), a.orgId(), method, amr, remember, userAgent, ip);
		jdbc.sql("update app_user set last_login_at = now() where id = :id").param("id", a.userId()).update();
		audit.log("LOGIN_OK", a.employeeId(), a.userId().toString(), Map.of("method", method, "ip", String.valueOf(ip), "device", TokenService.deviceLabel(userAgent)));
		return new Outcome(okBody(a, issued), issued);
	}

	Map<String, Object> okBody(Account a, TokenService.Issued issued) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("status", "OK");
		m.put("accessToken", issued.accessToken());
		m.put("expiresIn", TokenService.ACCESS_SECONDS);
		m.put("landing", landing(a.userId()));
		m.put("user", userView(a));
		return m;
	}

	Map<String, Object> userView(Account a) {
		Map<String, Object> u = new LinkedHashMap<>();
		u.put("id", a.userId());
		u.put("employeeId", a.employeeId());
		u.put("code", jdbc.sql("select employee_code from employee where id = :e").param("e", a.employeeId()).query(String.class).optional().orElse(null));
		u.put("name", a.name());
		u.put("email", a.email());
		String[] parts = a.name() == null ? new String[0] : a.name().trim().split("\\s+");
		u.put("initials", parts.length == 0 ? "" : ((parts[0].substring(0, 1)) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase());
		u.put("photoUrl", "/api/v1/employees/" + a.employeeId() + "/photo?s=64");
		return u;
	}

	@Transactional(noRollbackFor = StatusException.class)
	public Outcome refresh(String cookie, String ip, String userAgent) {
		TokenService.Issued issued = tokens.refresh(cookie, userAgent, ip);
		UUID userId = jdbc.sql("select user_id from auth_session where id = :s").param("s", issued.sessionId()).query(UUID.class).single();
		Account a = findById(userId);
		return new Outcome(okBody(a, issued), issued);
	}

	public void logout(String cookie) {
		UUID sid = tokens.sessionOf(cookie);
		if (sid != null) {
			tokens.revoke(sid, "SIGN_OUT");
		}
	}

	public Map<String, Object> me(UUID userId, UUID sessionId) {
		Account a = findById(userId);
		if (a == null) {
			throw new NotFoundException("User not found");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("user", userView(a));
		m.put("landing", landing(userId));
		m.put("consoleAccess", consoleAccess(userId));
		Map<String, Object> s = jdbc.sql("select id, remember, expires_at, amr from auth_session where id = :s").param("s", sessionId).query((rs, i) -> {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("id", rs.getObject(1, UUID.class));
			x.put("remember", rs.getBoolean(2));
			x.put("expiresAt", rs.getObject(3, OffsetDateTime.class));
			x.put("amr", List.of((String[]) rs.getArray(4).getArray()));
			return x;
		}).optional().orElse(null);
		m.put("amr", s == null ? List.of() : s.get("amr"));
		m.put("session", s);
		Map<String, Object> sec = new LinkedHashMap<>();
		sec.put("hasPassword", a.passwordHash() != null);
		sec.put("mfaEnrolled", a.totpSecret() != null && !a.mfaResetRequired());
		sec.put("passwordLoginAllowed", passwords.passwordLoginEnabled() || a.breakGlass());
		m.put("security", sec);
		return m;
	}

	// ================================================================== sessions list

	@Transactional(readOnly = true)
	public List<Map<String, Object>> sessions(UUID userId, UUID current) {
		return jdbc.sql("select id, device_label, place, last_active_at, created_at, method from auth_session where user_id = :u and revoked_at is null and absolute_expires_at > now() order by last_active_at desc")
				.param("u", userId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					UUID id = rs.getObject(1, UUID.class);
					m.put("id", id);
					m.put("device", rs.getString(2));
					m.put("place", rs.getString(3));
					m.put("lastActiveAt", rs.getObject(4, OffsetDateTime.class));
					m.put("createdAt", rs.getObject(5, OffsetDateTime.class));
					m.put("method", "SSO".equals(rs.getString(6)) ? "SSO" : "PASSWORD");
					m.put("current", id.equals(current));
					return m;
				}).list();
	}

	@Transactional
	public void revokeSession(UUID userId, UUID sessionId, UUID current) {
		if (sessionId.equals(current)) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_SESSION", "Use Sign out for this device");
		}
		UUID owner = jdbc.sql("select user_id from auth_session where id = :s").param("s", sessionId).query(UUID.class).optional().orElseThrow(() -> new NotFoundException("Session not found"));
		if (!owner.equals(userId)) {
			throw new NotFoundException("Session not found");
		}
		tokens.revoke(sessionId, "SIGNED_OUT_BY_USER");
		audit.log("SESSION_REVOKED", null, userId.toString(), Map.of("session", sessionId.toString()));
	}

	// ================================================================== tokens (reset, activate, challenge)

	String token(UUID userId, String purpose, Duration life, Map<String, Object> data) {
		String raw = AuthCrypto.randomToken();
		jdbc.sql("insert into auth_token (token_hash, user_id, purpose, data, expires_at) values (:h, :u, :p, cast(:d as jsonb), :e)").param("h", AuthCrypto.hash(raw)).param("u", userId)
				.param("p", purpose).param("d", json.writeValueAsString(data)).param("e", OffsetDateTime.now().plus(life)).update();
		return raw;
	}

	Map<String, Object> loadToken(String raw, String purpose, String code, String message) {
		if (raw == null || raw.isBlank()) {
			throw new StatusException(HttpStatus.GONE, code, message);
		}
		return jdbc.sql("select user_id, data::text, expires_at from auth_token where token_hash = :h and purpose = :p and used_at is null and expires_at > now()").param("h", AuthCrypto.hash(raw))
				.param("p", purpose).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("user", rs.getObject(1, UUID.class));
					m.put("data", rs.getString(2));
					return m;
				}).optional().orElseThrow(() -> new StatusException(HttpStatus.GONE, code, message));
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> dataOf(Map<String, Object> t) {
		return new LinkedHashMap<>(json.readValue((String) t.get("data"), Map.class));
	}

	// ================================================================== reset and change

	/** Always answers the same; only an account that can use a password gets a link. */
	@Transactional
	public void forgot(String rawEmail, String ip) {
		limiter.hit("forgot-ip:" + ip, 20, Duration.ofMinutes(15));
		String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
		Account a = email.isEmpty() ? null : find(email);
		if (a == null || !"ACTIVE".equals(a.status()) || "EXITED".equals(a.employeeStatus())) {
			return;
		}
		if (!limiter.tryHit("forgot:" + a.userId(), 3, Duration.ofHours(1))) {
			return;
		}
		if (!passwords.passwordLoginEnabled() && !a.breakGlass()) {
			notifier.email(a.email(), null, "Your HRMS account uses Microsoft sign-in", "<p>Hi " + esc(a.firstName()) + ", your account uses Microsoft sign-in. Use <b>Continue with Microsoft</b> on the sign-in page; there is no password to reset.</p>");
			return;
		}
		jdbc.sql("update auth_token set used_at = now() where user_id = :u and purpose = 'RESET' and used_at is null").param("u", a.userId()).update();
		String raw = token(a.userId(), "RESET", Duration.ofMinutes(30), Map.of());
		String link = authProps.appBaseUrl() + "/#reset?token=" + raw;
		notifier.email(a.email(), null, "Reset your HRMS password", "<p>Hi " + esc(a.firstName()) + ", use this link to choose a new password. It works once and expires in 30 minutes.</p><p><a href=\""
				+ link + "\">Reset password</a></p><p>If you didn't ask for this, you can ignore this email.</p>");
		audit.log("PASSWORD_RESET_REQUESTED", a.employeeId(), a.userId().toString(), null);
	}

	public Map<String, Object> resetInfo(String token) {
		Map<String, Object> t = loadToken(token, "RESET", "LINK_EXPIRED", "This link has expired. Request a new one.");
		Account a = findById((UUID) t.get("user"));
		String local = a.email().substring(0, a.email().indexOf('@'));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("email", local.substring(0, 1) + "***@" + a.email().substring(a.email().indexOf('@') + 1));
		m.put("policy", passwords.policy());
		return m;
	}

	@Transactional(noRollbackFor = StatusException.class)
	public void reset(String token, String newPassword) {
		Map<String, Object> t = loadToken(token, "RESET", "LINK_EXPIRED", "This link has expired. Request a new one.");
		UUID userId = (UUID) t.get("user");
		Account a = findById(userId);
		passwords.require(newPassword, userId, a.email(), a.name());
		passwords.set(userId, newPassword);
		jdbc.sql("update auth_token set used_at = now() where token_hash = :h").param("h", AuthCrypto.hash(token)).update();
		tokens.revokeAll(userId, null, "PASSWORD_RESET");
		audit.log("PASSWORD_RESET_DONE", a.employeeId(), userId.toString(), null);
	}

	@Transactional(noRollbackFor = StatusException.class)
	public void changePassword(UUID userId, UUID currentSession, String current, String next) {
		Account a = findById(userId);
		if (a == null || a.passwordHash() == null || !passwords.matches(current == null ? "" : current, a.passwordHash())) {
			throw new StatusException(HttpStatus.UNAUTHORIZED, "CURRENT_PASSWORD", "Current password is incorrect");
		}
		passwords.require(next, userId, a.email(), a.name());
		passwords.set(userId, next);
		tokens.revokeAll(userId, currentSession, "PASSWORD_CHANGED");
		audit.log("PASSWORD_CHANGED", a.employeeId(), userId.toString(), null);
	}

	// ================================================================== activation

	public Map<String, Object> activationInfo(String token) {
		Map<String, Object> t = loadToken(token, "ACTIVATE", "LINK_EXPIRED", "This link has expired. Ask IT to send a new one.");
		Account a = findById((UUID) t.get("user"));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", a.name());
		m.put("firstName", a.firstName());
		m.put("email", a.email());
		m.put("needsPassword", passwords.passwordLoginEnabled() || a.breakGlass());
		m.put("needsMfa", twoFaRequiredByOrg() || consoleAccess(a.userId()));
		m.put("policy", passwords.policy());
		return m;
	}

	@Transactional(noRollbackFor = StatusException.class)
	public Outcome activate(String token, String password, String ip, String userAgent) {
		Map<String, Object> t = loadToken(token, "ACTIVATE", "LINK_EXPIRED", "This link has expired. Ask IT to send a new one.");
		UUID userId = (UUID) t.get("user");
		Account a = findById(userId);
		if (a == null || "DISABLED".equals(a.status()) || "EXITED".equals(a.employeeStatus())) {
			throw new StatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Your account is disabled. Contact IT.");
		}
		boolean needsPassword = passwords.passwordLoginEnabled() || a.breakGlass();
		if (needsPassword) {
			passwords.require(password, userId, a.email(), a.name());
			passwords.set(userId, password);
		}
		jdbc.sql("update auth_token set used_at = now() where token_hash = :h").param("h", AuthCrypto.hash(token)).update();
		jdbc.sql("update app_user set status = 'ACTIVE', activated_at = coalesce(activated_at, now()) where id = :id").param("id", userId).update();
		audit.log("PASSWORD_CHANGED", a.employeeId(), userId.toString(), Map.of("activation", true));
		Account fresh = findById(userId);
		if (twoFaRequiredByOrg() || consoleAccess(userId)) {
			return pending(Map.of("status", "MFA_ENROLL_REQUIRED", "enrollToken", token(userId, "MFA_ENROLL", Duration.ofMinutes(15), Map.of("remember", true, "method", "ACTIVATION"))));
		}
		return signIn(fresh, "ACTIVATION", List.of("pwd"), true, ip, userAgent);
	}

	// ================================================================== admin (IT)

	@Transactional
	public void unlock(UUID userId) {
		jdbc.sql("update auth_credential set failed_count = 0, locked_until = null where user_id = :u").param("u", userId).update();
	}

	@Transactional
	public void resetMfa(UUID userId) {
		Account a = findById(userId);
		if (a == null) {
			throw new NotFoundException("User not found");
		}
		jdbc.sql("update auth_credential set totp_secret_enc = null, totp_enrolled_at = null, totp_last_step = null, mfa_reset_required = true where user_id = :u").param("u", userId).update();
		jdbc.sql("delete from auth_recovery_code where user_id = :u").param("u", userId).update();
		tokens.revokeAll(userId, null, "MFA_RESET");
		audit.log("MFA_RESET", a.employeeId(), userId.toString(), null);
	}

	static String esc(String s) {
		return org.springframework.web.util.HtmlUtils.htmlEscape(s == null ? "" : s);
	}

	static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
