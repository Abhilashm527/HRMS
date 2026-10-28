package com.ivoyant.hrms.iam.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.common.web.StatusException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Access tokens (RS256, 15 minutes), rotating refresh tokens, and the session rows behind them. */
@Service
public class TokenService {

	static final String COOKIE = "hrms_rt";
	static final int ACCESS_SECONDS = 900;
	private static final Duration REMEMBER = Duration.ofDays(30);
	private static final Duration REMEMBER_MAX = Duration.ofDays(90);
	private static final Duration SHORT = Duration.ofHours(12);
	private static final int MAX_SESSIONS = 10;

	record Issued(String accessToken, String refreshToken, UUID sessionId, boolean remember, Instant refreshExpires) {
	}

	private final JdbcClient jdbc;
	private final KeyService keys;
	private final AuthProps props;
	private final SecurityAudit audit;
	/** session id → still active; an entry lives one minute, revoking on this instance drops it at once. */
	private final Cache<UUID, Boolean> active = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(50_000).build();

	TokenService(JdbcClient jdbc, KeyService keys, AuthProps props, SecurityAudit audit) {
		this.jdbc = jdbc;
		this.keys = keys;
		this.props = props;
		this.audit = audit;
	}

	// ------------------------------------------------------------------ access token

	String access(UUID userId, UUID employeeId, UUID orgId, UUID sessionId, List<String> amr) {
		try {
			Instant now = Instant.now();
			JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(props.issuer()).subject(userId.toString()).claim("emp", employeeId.toString()).claim("org", orgId.toString())
					.claim("sid", sessionId.toString()).claim("amr", amr).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(ACCESS_SECONDS)))
					.jwtID(UUID.randomUUID().toString()).build();
			RSAKey key = keys.signingKey();
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Used by the decoder: is the session of this token still alive? */
	boolean sessionActive(UUID sid) {
		Boolean cached = active.getIfPresent(sid);
		if (cached != null) {
			return cached;
		}
		boolean ok = jdbc.sql("select count(*) from auth_session where id = :id and revoked_at is null and absolute_expires_at > now()").param("id", sid).query(Long.class).single() > 0;
		active.put(sid, ok);
		return ok;
	}

	// ------------------------------------------------------------------ sessions

	/** Starts a session (device) and its first refresh token. Beyond ten live sessions the oldest is ended. */
	@Transactional
	Issued start(UUID userId, UUID employeeId, UUID orgId, String method, List<String> amr, boolean remember, String userAgent, String ip) {
		List<UUID> live = jdbc.sql("select id from auth_session where user_id = :u and revoked_at is null order by last_active_at desc").param("u", userId).query(UUID.class).list();
		for (int i = MAX_SESSIONS - 1; i < live.size(); i++) {
			revoke(live.get(i), "EVICTED");
		}
		UUID sid = UUID.randomUUID();
		Instant now = Instant.now();
		Duration life = remember ? REMEMBER : SHORT;
		jdbc.sql("""
				insert into auth_session (id, user_id, org_id, method, amr, remember, user_agent, device_label, ip, expires_at, absolute_expires_at)
				values (:id, :u, :org, :m, :amr, :rem, :ua, :dev, cast(:ip as inet), :exp, :abs)""").param("id", sid).param("u", userId).param("org", orgId).param("m", method)
				.param("amr", amr.toArray(String[]::new)).param("rem", remember).param("ua", userAgent).param("dev", deviceLabel(userAgent)).param("ip", validIp(ip))
				.param("exp", OffsetDateTime.ofInstant(now.plus(life), java.time.ZoneOffset.UTC))
				.param("abs", OffsetDateTime.ofInstant(now.plus(remember ? REMEMBER_MAX : SHORT), java.time.ZoneOffset.UTC)).update();
		active.put(sid, true);
		String refresh = newRefresh(sid, UUID.randomUUID(), now.plus(life));
		return new Issued(access(userId, employeeId, orgId, sid, amr), refresh, sid, remember, now.plus(life));
	}

	private String newRefresh(UUID sid, UUID family, Instant expires) {
		String raw = AuthCrypto.randomToken();
		jdbc.sql("insert into auth_refresh_token (token_hash, session_id, family, expires_at) values (:h, :s, :f, :e)").param("h", AuthCrypto.hash(raw)).param("s", sid).param("f", family)
				.param("e", OffsetDateTime.ofInstant(expires, java.time.ZoneOffset.UTC)).update();
		return raw;
	}

	/** Rotates a refresh token. A token that was already used means it leaked: the whole session is ended. */
	@Transactional(noRollbackFor = StatusException.class)
	Issued refresh(String raw, String userAgent, String ip) {
		if (raw == null || raw.isBlank()) {
			throw unauthorized("TOKEN_EXPIRED", "Please sign in again");
		}
		Map<String, Object> t = jdbc.sql("""
				select t.session_id, t.family, t.used_at, t.expires_at, s.user_id, s.org_id, s.remember, s.amr, s.revoked_at, s.absolute_expires_at, s.last_active_at, u.employee_id, u.status
				from auth_refresh_token t join auth_session s on s.id = t.session_id join app_user u on u.id = s.user_id where t.token_hash = :h""")
				.param("h", AuthCrypto.hash(raw)).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("sid", rs.getObject(1, UUID.class));
					m.put("family", rs.getObject(2, UUID.class));
					m.put("used", rs.getObject(3));
					m.put("expires", rs.getObject(4, OffsetDateTime.class));
					m.put("user", rs.getObject(5, UUID.class));
					m.put("org", rs.getObject(6, UUID.class));
					m.put("remember", rs.getBoolean(7));
					m.put("amr", List.of((String[]) rs.getArray(8).getArray()));
					m.put("revoked", rs.getObject(9));
					m.put("absolute", rs.getObject(10, OffsetDateTime.class));
					m.put("lastActive", rs.getObject(11, OffsetDateTime.class));
					m.put("emp", rs.getObject(12, UUID.class));
					m.put("status", rs.getString(13));
					return m;
				}).optional().orElseThrow(() -> unauthorized("TOKEN_EXPIRED", "Please sign in again"));
		UUID sid = (UUID) t.get("sid");
		UUID user = (UUID) t.get("user");
		if (t.get("revoked") != null) {
			throw unauthorized("SESSION_REVOKED", "You were signed out");
		}
		int claimed = jdbc.sql("update auth_refresh_token set used_at = now() where token_hash = :h and used_at is null").param("h", AuthCrypto.hash(raw)).update();
		if (claimed == 0) {
			revoke(sid, "REFRESH_REUSE");
			audit.log("REFRESH_REUSE", (UUID) t.get("emp"), user.toString(), Map.of("session", sid.toString()));
			throw unauthorized("SESSION_REVOKED", "You were signed out for your security. Sign in again.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		if (((OffsetDateTime) t.get("expires")).isBefore(now) || ((OffsetDateTime) t.get("absolute")).isBefore(now)) {
			revoke(sid, "EXPIRED");
			throw unauthorized("TOKEN_EXPIRED", "Please sign in again");
		}
		if (!"ACTIVE".equals(t.get("status")) || jdbc.sql("select status from employee where id = :e").param("e", t.get("emp")).query(String.class).optional().orElse("EXITED").equals("EXITED")) {
			revoke(sid, "DISABLED");
			throw unauthorized("SESSION_REVOKED", "Your account is disabled. Contact IT.");
		}
		boolean remember = (Boolean) t.get("remember");
		Instant next = Instant.now().plus(remember ? REMEMBER : SHORT);
		Instant cap = ((OffsetDateTime) t.get("absolute")).toInstant();
		if (next.isAfter(cap)) {
			next = cap;
		}
		if (((OffsetDateTime) t.get("lastActive")).isBefore(now.minusMinutes(15))) {
			jdbc.sql("update auth_session set last_active_at = now(), expires_at = :e, ip = coalesce(cast(:ip as inet), ip), user_agent = coalesce(:ua, user_agent) where id = :id")
					.param("e", OffsetDateTime.ofInstant(next, java.time.ZoneOffset.UTC)).param("ip", validIp(ip)).param("ua", userAgent).param("id", sid).update();
		}
		@SuppressWarnings("unchecked")
		List<String> amr = (List<String>) t.get("amr");
		String newRaw = newRefresh(sid, (UUID) t.get("family"), next);
		return new Issued(access(user, (UUID) t.get("emp"), (UUID) t.get("org"), sid, amr), newRaw, sid, remember, next);
	}

	void revoke(UUID sid, String reason) {
		jdbc.sql("update auth_session set revoked_at = now(), revoked_reason = :r where id = :id and revoked_at is null").param("r", reason).param("id", sid).update();
		active.invalidate(sid);
	}

	/** Ends every session of the user, except {@code keep} when given. */
	@Transactional
	public int revokeAll(UUID userId, UUID keep, String reason) {
		List<UUID> ids = jdbc.sql("select id from auth_session where user_id = :u and revoked_at is null and (cast(:k as uuid) is null or id <> :k)").param("u", userId)
				.param("k", keep, java.sql.Types.OTHER).query(UUID.class).list();
		ids.forEach(id -> revoke(id, reason));
		return ids.size();
	}

	/** The session a refresh cookie belongs to (for sign out), or null. */
	UUID sessionOf(String raw) {
		return raw == null ? null : jdbc.sql("select session_id from auth_refresh_token where token_hash = :h").param("h", AuthCrypto.hash(raw)).query(UUID.class).optional().orElse(null);
	}

	void touchCleanup() {
		jdbc.sql("delete from auth_token where expires_at < now() - interval '1 day' or used_at < now() - interval '7 days'").update();
		jdbc.sql("delete from auth_refresh_token where expires_at < now() - interval '7 days'").update();
		jdbc.sql("delete from auth_session where revoked_at < now() - interval '90 days' or absolute_expires_at < now() - interval '90 days'").update();
	}

	// ------------------------------------------------------------------ cookie and device

	ResponseCookie cookie(Issued issued) {
		ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE, issued.refreshToken()).httpOnly(true).secure(props.secureCookies()).sameSite("Strict").path("/api/v1/auth");
		if (issued.remember()) {
			b.maxAge(Duration.between(Instant.now(), issued.refreshExpires()));
		}
		return b.build();
	}

	ResponseCookie clearCookie() {
		return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.secureCookies()).sameSite("Strict").path("/api/v1/auth").maxAge(0).build();
	}

	private static final Pattern BROWSER = Pattern.compile("(Edg|OPR|Firefox|Chrome|Safari|PostmanRuntime|curl)/");

	static String deviceLabel(String ua) {
		if (ua == null || ua.isBlank()) {
			return "Unknown device";
		}
		String browser = "Browser";
		Matcher m = BROWSER.matcher(ua);
		String first = null;
		while (m.find()) {
			String name = m.group(1);
			if (first == null || name.equals("Edg") || name.equals("OPR") || (name.equals("Chrome") && first.equals("Safari"))) {
				first = name;
			}
		}
		if (first != null) {
			browser = switch (first) {
				case "Edg" -> "Edge";
				case "OPR" -> "Opera";
				case "PostmanRuntime" -> "Postman";
				default -> first;
			};
		}
		String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android" : ua.contains("Windows") ? "Windows"
				: ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS" : ua.contains("Linux") ? "Linux" : "";
		return os.isEmpty() ? browser : browser + " · " + os;
	}

	private static String validIp(String ip) {
		if (ip == null) {
			return null;
		}
		String v = ip.contains(",") ? ip.substring(0, ip.indexOf(',')).trim() : ip.trim();
		return v.matches("[0-9a-fA-F:.]+") ? v : null;
	}

	static StatusException unauthorized(String code, String message) {
		return new StatusException(HttpStatus.UNAUTHORIZED, code, message);
	}
}
