package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.StatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** My account: change password, my sessions, two-step settings. And the IT tools for someone else's sign-in. */
@RestController
@RequestMapping("/api/v1")
public class MeSecurityController {

	private final AuthService auth;
	private final TokenService tokens;
	private final AuthInvites invites;
	private final CurrentUser currentUser;
	private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

	public MeSecurityController(AuthService auth, TokenService tokens, AuthInvites invites, CurrentUser currentUser, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
		this.auth = auth;
		this.tokens = tokens;
		this.invites = invites;
		this.currentUser = currentUser;
		this.jdbc = jdbc;
	}

	/** The signed-in user's app_user id and session id. The local dev header has no session. */
	private UUID[] who() {
		Object p = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (p instanceof Jwt jwt) {
			return new UUID[] {UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid"))};
		}
		UUID emp = currentUser.requireEmployeeId();
		UUID user = jdbc.sql("select id from app_user where employee_id = :e").param("e", emp).query(UUID.class).optional()
				.orElseThrow(() -> new StatusException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Please sign in"));
		return new UUID[] {user, new UUID(0, 0)};
	}

	@PostMapping("/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void changePassword(@RequestBody Map<String, Object> body) {
		UUID[] w = who();
		auth.changePassword(w[0], w[1], str(body.get("currentPassword")), str(body.get("newPassword")));
	}

	@GetMapping("/me/sessions")
	@RequiresMember
	public List<Map<String, Object>> sessions() {
		UUID[] w = who();
		return auth.sessions(w[0], w[1]);
	}

	@DeleteMapping("/me/sessions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void endSession(@PathVariable UUID id) {
		UUID[] w = who();
		auth.revokeSession(w[0], id, w[1]);
	}

	@DeleteMapping("/me/sessions")
	@RequiresMember
	public Map<String, Object> endOthers(@RequestParam(defaultValue = "true") boolean others) {
		UUID[] w = who();
		return Map.of("signedOut", tokens.revokeAll(w[0], w[1], "SIGNED_OUT_BY_USER"));
	}

	/** Start (re-)enrolling two-step verification; needs the current password. Continue with the /auth/mfa/enroll calls. */
	@PostMapping("/me/mfa/enroll")
	@RequiresMember
	public Map<String, Object> reenroll(@RequestBody Map<String, Object> body) {
		return auth.startReenroll(who()[0], str(body.get("currentPassword")));
	}

	@PostMapping("/me/mfa/recovery-codes")
	@RequiresMember
	public Map<String, Object> recovery(@RequestBody Map<String, Object> body) {
		return Map.of("recoveryCodes", auth.regenerateRecovery(who()[0], str(body.get("code"))));
	}

	// ---- IT: someone else's sign-in ----

	@GetMapping("/users/{id}/sessions")
	@RequiresHrAdmin
	public List<Map<String, Object>> userSessions(@PathVariable UUID id) {
		return auth.sessions(id, null);
	}

	@DeleteMapping("/users/{id}/sessions")
	@RequiresHrAdmin
	public Map<String, Object> endUserSessions(@PathVariable UUID id) {
		return Map.of("signedOut", tokens.revokeAll(id, null, "ENDED_BY_ADMIN"));
	}

	@PostMapping("/users/{id}/mfa/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresHrAdmin
	public void resetMfa(@PathVariable UUID id) {
		auth.resetMfa(id);
	}

	@PostMapping("/users/{id}/unlock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresHrAdmin
	public void unlock(@PathVariable UUID id) {
		auth.unlock(id);
	}

	@PostMapping("/users/{id}/invite")
	@RequiresHrAdmin
	public Map<String, Object> invite(@PathVariable UUID id) {
		UUID employee = jdbc.sql("select employee_id from app_user where id = :id").param("id", id).query(UUID.class).optional()
				.orElseThrow(() -> new com.ivoyant.hrms.common.web.NotFoundException("User not found"));
		invites.send(employee);
		return Map.of("sent", true);
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
