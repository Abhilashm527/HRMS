package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.config.HrmsProperties;
import com.ivoyant.hrms.common.web.StatusException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

/** Password rules from Organization → Security, Argon2id hashing, and the history that stops reuse of the last five. */
@Service
public class PasswordService {

	public record Policy(String mode, int minLength, boolean complex, String hint) {
	}

	private static final int HISTORY = 5;

	private final JdbcClient jdbc;
	private final HrmsProperties props;
	private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
	private final Set<String> common = new HashSet<>();
	private final String dummyHash;

	PasswordService(JdbcClient jdbc, HrmsProperties props) {
		this.jdbc = jdbc;
		this.props = props;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new ClassPathResource("auth/common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
			r.lines().map(String::trim).filter(s -> !s.isEmpty()).forEach(common::add);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Common password list missing", ex);
		}
		this.dummyHash = encoder.encode("dummy-password-for-timing");
	}

	/** The organization setting: STANDARD, STRONG, or ENTRA (passwords are off except for break-glass accounts, which use the strong rules). */
	public String mode() {
		return jdbc.sql("select pw_policy from org_settings where org_id = :org").param("org", props.defaultOrgId()).query(String.class).optional().orElse("ENTRA");
	}

	public boolean passwordLoginEnabled() {
		return !"ENTRA".equals(mode());
	}

	public Policy policy() {
		String mode = mode();
		return "STANDARD".equals(mode) ? new Policy(mode, 8, false, "8+ characters")
				: new Policy(mode, 12, true, "12+ characters with upper and lower case, a digit and a symbol");
	}

	public List<String> violations(String password, String email, String name) {
		Policy p = policy();
		List<String> out = new ArrayList<>();
		String pw = password == null ? "" : password;
		if (pw.length() < p.minLength()) {
			out.add("At least " + p.minLength() + " characters");
		}
		if (p.complex()) {
			if (!pw.matches(".*[A-Z].*")) {
				out.add("An upper-case letter");
			}
			if (!pw.matches(".*[a-z].*")) {
				out.add("A lower-case letter");
			}
			if (!pw.matches(".*\\d.*")) {
				out.add("A digit");
			}
			if (!pw.matches(".*[^A-Za-z0-9].*")) {
				out.add("A symbol");
			}
		}
		String low = pw.toLowerCase(Locale.ROOT);
		String local = email == null ? "" : email.toLowerCase(Locale.ROOT).replaceAll("@.*", "");
		if (!low.isEmpty() && (low.equals(email == null ? "" : email.toLowerCase(Locale.ROOT)) || low.equals(local) || (name != null && low.equals(name.toLowerCase(Locale.ROOT))))) {
			out.add("Not your email or name");
		}
		if (common.contains(low)) {
			out.add("Not a commonly used password");
		}
		return out;
	}

	/** Throws 422 PASSWORD_POLICY with the rules that failed, or PASSWORD_REUSED. */
	public void require(String password, UUID userId, String email, String name) {
		List<String> broken = violations(password, email, name);
		if (!broken.isEmpty()) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_POLICY", "Password does not meet the rules: " + String.join("; ", broken), Map.of("rules", broken));
		}
		if (userId != null) {
			for (String old : jdbc.sql("select password_hash from auth_password_history where user_id = :u order by set_at desc limit " + HISTORY).param("u", userId).query(String.class).list()) {
				if (encoder.matches(password, old)) {
					throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PASSWORD_REUSED", "Choose a password you haven't used");
				}
			}
		}
	}

	public String hash(String password) {
		return encoder.encode(password);
	}

	public boolean matches(String password, String hash) {
		return hash != null && encoder.matches(password, hash);
	}

	/** Takes as long as a real check, so an unknown email cannot be told from a wrong password by timing. */
	void burn(String password) {
		encoder.matches(password == null ? "" : password, dummyHash);
	}

	/** Stores the new password and remembers it in the history; clears a lockout. */
	void set(UUID userId, String password) {
		String h = hash(password);
		jdbc.sql("""
				insert into auth_credential (user_id, password_hash, password_set_at) values (:u, :h, now())
				on conflict (user_id) do update set password_hash = :h, password_set_at = now(), failed_count = 0, locked_until = null, version = auth_credential.version + 1""")
				.param("u", userId).param("h", h).update();
		jdbc.sql("insert into auth_password_history (user_id, password_hash) values (:u, :h)").param("u", userId).param("h", h).update();
		jdbc.sql("delete from auth_password_history where user_id = :u and set_at < (select set_at from auth_password_history where user_id = :u order by set_at desc offset 9 limit 1)").param("u", userId).update();
	}
}
