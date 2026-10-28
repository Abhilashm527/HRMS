package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.web.StatusException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Everything before and around sign-in. All of it is open to anyone; the cookie-based calls also check where the request came from. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService auth;
	private final SsoService sso;
	private final PasswordService passwords;
	private final AuthProps props;
	private final TokenService tokens;

	public AuthController(AuthService auth, SsoService sso, PasswordService passwords, AuthProps props, TokenService tokens) {
		this.auth = auth;
		this.sso = sso;
		this.passwords = passwords;
		this.props = props;
		this.tokens = tokens;
	}

	@GetMapping("/config")
	public Map<String, Object> config() {
		return auth.config();
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		AuthService.Outcome o = auth.login(str(body.get("email")), str(body.get("password")), !Boolean.FALSE.equals(body.get("remember")), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
		return respond(o);
	}

	@PostMapping("/mfa/verify")
	public ResponseEntity<Map<String, Object>> mfa(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		return respond(auth.verifyMfa(str(body.get("challengeId")), str(body.get("code")), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/mfa/enroll/start")
	public Map<String, Object> enrollStart(@RequestBody Map<String, Object> body) {
		return auth.enrollStart(str(body.get("enrollToken")));
	}

	@PostMapping("/mfa/enroll/confirm")
	public ResponseEntity<Map<String, Object>> enrollConfirm(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		return respond(auth.enrollConfirm(str(body.get("enrollToken")), str(body.get("code")), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
	}

	// ---- Microsoft ----

	@GetMapping("/sso/microsoft/start")
	public ResponseEntity<Void> ssoStart(@RequestParam(required = false) String loginHint, @RequestParam(defaultValue = "true") boolean remember, @RequestParam(required = false) String returnTo) {
		SsoService.Start s = sso.start(loginHint, remember, returnTo);
		ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(s.redirect()));
		if (s.cookie() != null) {
			b.header(HttpHeaders.SET_COOKIE, s.cookie().toString());
		}
		return b.build();
	}

	@GetMapping("/sso/microsoft/callback")
	public ResponseEntity<Void> ssoCallback(@RequestParam(required = false) String code, @RequestParam(required = false) String state, @RequestParam(required = false) String error,
			HttpServletRequest request) {
		SsoService.Done d = sso.callback(code, state, error, cookie(request, SsoService.STATE_COOKIE), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
		ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(d.redirect()));
		b.header(HttpHeaders.SET_COOKIE, org.springframework.http.ResponseCookie.from(SsoService.STATE_COOKIE, "").path("/api/v1/auth").maxAge(0).build().toString());
		if (d.issued() != null) {
			b.header(HttpHeaders.SET_COOKIE, tokens.cookie(d.issued()).toString());
		}
		return b.build();
	}

	// ---- session ----

	@PostMapping("/refresh")
	public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
		requireSameOrigin(request);
		return respond(auth.refresh(cookie(request, TokenService.COOKIE), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		requireSameOrigin(request);
		auth.logout(cookie(request, TokenService.COOKIE));
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tokens.clearCookie().toString()).build();
	}

	@GetMapping("/me")
	public Map<String, Object> me() {
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) {
			throw new StatusException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Please sign in");
		}
		return auth.me(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
	}

	// ---- password ----

	@GetMapping("/password/policy")
	public Map<String, Object> policy() {
		PasswordService.Policy p = passwords.policy();
		return Map.of("mode", p.mode(), "minLength", p.minLength(), "complex", p.complex(), "hint", p.hint());
	}

	@PostMapping("/password/forgot")
	public ResponseEntity<Map<String, Object>> forgot(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		auth.forgot(str(body.get("email")), request.getRemoteAddr());
		return ResponseEntity.accepted().body(Map.of("message", "If that address has an account, a reset link is on its way."));
	}

	@GetMapping("/password/reset/{token}")
	public Map<String, Object> resetInfo(@PathVariable String token) {
		return auth.resetInfo(token);
	}

	@PostMapping("/password/reset")
	public ResponseEntity<Void> reset(@RequestBody Map<String, Object> body) {
		auth.reset(str(body.get("token")), str(body.get("newPassword")));
		return ResponseEntity.noContent().build();
	}

	// ---- activation ----

	@GetMapping("/activate/{token}")
	public Map<String, Object> activateInfo(@PathVariable String token) {
		return auth.activationInfo(token);
	}

	@PostMapping("/activate")
	public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
		return respond(auth.activate(str(body.get("token")), str(body.get("password")), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
	}

	// ---- helpers ----

	private ResponseEntity<Map<String, Object>> respond(AuthService.Outcome o) {
		ResponseEntity.BodyBuilder b = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
		if (o.issued() != null) {
			b.header(HttpHeaders.SET_COOKIE, tokens.cookie(o.issued()).toString());
		}
		return b.body(o.body());
	}

	private void requireSameOrigin(HttpServletRequest request) {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin != null && !props.local() && !origin.equals(props.appBaseUrl()) && !origin.equals(props.issuer())) {
			throw new StatusException(HttpStatus.FORBIDDEN, "BAD_ORIGIN", "This request did not come from the app");
		}
	}

	static String cookie(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie c : request.getCookies()) {
			if (c.getName().equals(name)) {
				return c.getValue();
			}
		}
		return null;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
