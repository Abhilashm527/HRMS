package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.config.HrmsProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Microsoft sign-in: the OIDC authorization-code flow with PKCE against the organization's Entra tenant. The id token is checked
 * (signature, issuer, audience, tenant, nonce) and the person is matched by their Entra object id, or by work email the first time.
 * We then issue our own session; Entra is not asked again.
 */
@Service
public class SsoService {

	private static final Logger log = LoggerFactory.getLogger(SsoService.class);
	static final String STATE_COOKIE = "hrms_sso";

	record Start(String redirect, ResponseCookie cookie) {
	}

	record Done(String redirect, TokenService.Issued issued) {
	}

	private final AuthProps props;
	private final HrmsProperties hrms;
	private final AuthCrypto crypto;
	private final AuthService auth;
	private final JdbcClient jdbc;
	private final SecurityAudit audit;
	private volatile NimbusJwtDecoder idDecoder;

	SsoService(AuthProps props, HrmsProperties hrms, AuthCrypto crypto, AuthService auth, JdbcClient jdbc, SecurityAudit audit) {
		this.props = props;
		this.hrms = hrms;
		this.crypto = crypto;
		this.auth = auth;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	private String redirectUri() {
		return props.issuer() + "/api/v1/auth/sso/microsoft/callback";
	}

	private String failure(String error) {
		return props.appBaseUrl() + "/#signin?error=" + error;
	}

	Start start(String loginHint, boolean remember, String returnTo) {
		if (!props.ssoEnabled()) {
			return new Start(failure("SSO_FAILED"), null);
		}
		String state = AuthCrypto.randomToken();
		String nonce = AuthCrypto.randomToken();
		String verifier = AuthCrypto.randomToken() + AuthCrypto.randomToken().substring(0, 11);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(AuthCrypto.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
		String safeReturn = returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains("\\") ? returnTo : "";
		String payload = String.join("|", state, nonce, verifier, Boolean.toString(remember), AuthService.b64(safeReturn), Long.toString(Instant.now().plusSeconds(600).getEpochSecond()));
		String value = AuthService.b64(payload) + "." + crypto.sign(payload);
		ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, value).httpOnly(true).secure(props.secureCookies()).sameSite("Lax").path("/api/v1/auth").maxAge(600).build();
		StringBuilder url = new StringBuilder("https://login.microsoftonline.com/").append(props.entraTenant()).append("/oauth2/v2.0/authorize?client_id=").append(enc(props.entraClient()))
				.append("&response_type=code&response_mode=query&scope=").append(enc("openid profile email")).append("&redirect_uri=").append(enc(redirectUri())).append("&state=")
				.append(enc(state)).append("&nonce=").append(enc(nonce)).append("&code_challenge=").append(enc(challenge)).append("&code_challenge_method=S256&prompt=select_account");
		if (loginHint != null && !loginHint.isBlank()) {
			url.append("&login_hint=").append(enc(loginHint.trim()));
		}
		return new Start(url.toString(), cookie);
	}

	Done callback(String code, String state, String error, String cookieValue, String ip, String userAgent) {
		if (error != null || code == null || state == null || cookieValue == null || !props.ssoEnabled()) {
			return new Done(failure("SSO_FAILED"), null);
		}
		String[] cookieParts = cookieValue.split("\\.", 2);
		if (cookieParts.length != 2) {
			return new Done(failure("SSO_FAILED"), null);
		}
		String payload = new String(Base64.getUrlDecoder().decode(cookieParts[0]), StandardCharsets.UTF_8);
		if (!crypto.verify(payload, cookieParts[1])) {
			return new Done(failure("SSO_FAILED"), null);
		}
		String[] p = payload.split("\\|", -1);
		if (p.length != 6 || Long.parseLong(p[5]) < Instant.now().getEpochSecond() || !MessageDigest.isEqual(p[0].getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
			return new Done(failure("SSO_FAILED"), null);
		}
		String nonce = p[1];
		boolean remember = Boolean.parseBoolean(p[3]);
		Jwt id;
		try {
			id = decoder().decode(exchange(code, p[2]));
		}
		catch (RuntimeException ex) {
			log.warn("Microsoft sign-in failed: {}", ex.getMessage());
			return new Done(failure("SSO_FAILED"), null);
		}
		if (!nonce.equals(id.getClaimAsString("nonce")) || !props.entraTenant().equalsIgnoreCase(id.getClaimAsString("tid"))) {
			return new Done(failure("SSO_FAILED"), null);
		}
		String oid = id.getClaimAsString("oid");
		String email = firstNonBlank(id.getClaimAsString("email"), id.getClaimAsString("preferred_username"));
		AuthService.Account a = byOid(oid);
		if (a == null && email != null) {
			a = auth.find(email.trim().toLowerCase(Locale.ROOT));
			if (a != null) {
				String bound = jdbc.sql("select external_subject from app_user where id = :id").param("id", a.userId()).query(String.class).optional().orElse(null);
				if (bound != null && !bound.equals(oid)) {
					a = null;
				}
				else {
					jdbc.sql("update app_user set external_subject = :s where id = :id and external_subject is null").param("s", oid).param("id", a.userId()).update();
				}
			}
		}
		if (a == null) {
			return new Done(failure("SSO_NOT_LINKED"), null);
		}
		if ("DISABLED".equals(a.status()) || "EXITED".equals(a.employeeStatus())) {
			return new Done(failure("ACCOUNT_DISABLED"), null);
		}
		if ("INVITED".equals(a.status())) {
			jdbc.sql("update app_user set status = 'ACTIVE', activated_at = coalesce(activated_at, now()) where id = :id").param("id", a.userId()).update();
		}
		List<String> amr = id.getClaimAsStringList("amr");
		if (auth.twoFaRequiredByOrg() && (amr == null || !amr.contains("mfa"))) {
			audit.log("SSO_WITHOUT_MFA", a.employeeId(), a.userId().toString(), Map.of("email", a.email()));
		}
		AuthService.Outcome out = auth.signIn(a, "SSO", List.of("sso"), remember, ip, userAgent);
		String back = p[4].isEmpty() ? "" : new String(Base64.getUrlDecoder().decode(p[4]), StandardCharsets.UTF_8);
		return new Done(props.appBaseUrl() + "/#signed-in" + (back.isEmpty() ? "" : "?returnTo=" + enc(back)), out.issued());
	}

	private AuthService.Account byOid(String oid) {
		if (oid == null) {
			return null;
		}
		UUID userId = jdbc.sql("select id from app_user where org_id = :org and external_subject = :s").param("org", hrms.defaultOrgId()).param("s", oid).query(UUID.class).optional().orElse(null);
		return userId == null ? null : auth.findById(userId);
	}

	private String exchange(String code, String verifier) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", props.entraClient());
		form.add("client_secret", props.entraSecret());
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", redirectUri());
		form.add("code_verifier", verifier);
		@SuppressWarnings("unchecked")
		Map<String, Object> res = RestClient.create().post().uri("https://login.microsoftonline.com/" + props.entraTenant() + "/oauth2/v2.0/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form).retrieve().body(Map.class);
		Object idToken = res == null ? null : res.get("id_token");
		if (idToken == null) {
			throw new IllegalStateException("No id_token in the token response");
		}
		return idToken.toString();
	}

	private NimbusJwtDecoder decoder() {
		if (idDecoder == null) {
			NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri("https://login.microsoftonline.com/" + props.entraTenant() + "/discovery/v2.0/keys").build();
			OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(props.entraClient()) ? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
			d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://login.microsoftonline.com/" + props.entraTenant() + "/v2.0"), audience));
			idDecoder = d;
		}
		return idDecoder;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String firstNonBlank(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}
}
