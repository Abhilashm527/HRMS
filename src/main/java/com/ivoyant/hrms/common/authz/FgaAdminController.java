package com.ivoyant.hrms.common.authz;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ForbiddenException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/fga")
public class FgaAdminController {

	private final FgaSyncService sync;
	private final AuthzService authz;
	private final CurrentUser currentUser;
	private final JdbcClient jdbc;
	private final FgaProperties props;

	public FgaAdminController(FgaSyncService sync, AuthzService authz, CurrentUser currentUser, JdbcClient jdbc, FgaProperties props) {
		this.sync = sync;
		this.authz = authz;
		this.currentUser = currentUser;
		this.jdbc = jdbc;
		this.props = props;
	}

	/**
	 * Re-derives every OpenFGA tuple from the database (idempotent, additive). This is the repair for an OpenFGA that is
	 * empty or behind, so it can't depend only on OpenFGA: a super admin according to the database may run it too.
	 */
	@PostMapping("/sync")
	public Map<String, Integer> sync() {
		if (!superAdminInDatabase() && !authz.isSuperAdmin()) {
			throw new ForbiddenException("Only a super admin can sync permissions");
		}
		return Map.of("queued", sync.syncAll());
	}

	/**
	 * Connection and sync health for Configuration → OpenFGA: the settings in use (the token is never returned and ids are shortened),
	 * whether OpenFGA answers, and the outbox of tuple changes waiting to be written.
	 */
	@GetMapping("/status")
	public Map<String, Object> status() {
		requireSuperAdmin();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("apiUrl", props.apiUrl());
		out.put("storeId", mask(authz.storeId()));
		out.put("modelId", mask(props.authorizationModelId()));
		out.put("tokenSet", props.apiToken() != null && !props.apiToken().isBlank());
		out.put("configured", authz.configured());
		long t0 = System.nanoTime();
		out.put("health", health());
		out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
		Map<String, Object> q = jdbc.sql("""
				select count(*) filter (where processed_at is null and gave_up_at is null) as pending,
				       count(*) filter (where processed_at is null and gave_up_at is null and attempts > 0) as failing,
				       count(*) filter (where processed_at is null and gave_up_at is not null) as gave_up,
				       count(*) filter (where processed_at > now() - interval '24 hours') as done_24h,
				       max(processed_at) as last_processed
				  from fga_outbox""").query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("pending", rs.getLong(1));
					m.put("failing", rs.getLong(2));
					m.put("gaveUp", rs.getLong(3));
					m.put("written24h", rs.getLong(4));
					m.put("lastWrittenAt", rs.getObject(5, OffsetDateTime.class));
					return m;
				}).single();
		q.put("lastError", jdbc.sql("select last_error from fga_outbox where processed_at is null and last_error is not null order by created_at desc limit 1")
				.query(String.class).optional().orElse(null));
		out.put("outbox", q);
		return out;
	}

	private String health() {
		try {
			HttpResponse<Void> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
					.send(HttpRequest.newBuilder(URI.create(props.apiUrl().replaceAll("/+$", "") + "/healthz")).timeout(Duration.ofSeconds(6)).GET().build(),
							HttpResponse.BodyHandlers.discarding());
			return r.statusCode() == 200 ? "UP" : "HTTP " + r.statusCode();
		}
		catch (IOException | IllegalArgumentException e) {
			return "UNREACHABLE";
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "UNREACHABLE";
		}
	}

	/** The authorization model as written in {@code fga/model.fga}. */
	@GetMapping("/model")
	public Map<String, Object> model() throws IOException {
		requireSuperAdmin();
		return Map.of("dsl", new ClassPathResource("fga/model.fga").getContentAsString(StandardCharsets.UTF_8));
	}

	/** {@code {user, relation, object}}, e.g. {@code employee:<id>}, {@code can_view_basic}, {@code employee:<id>}. */
	@PostMapping("/check")
	public Map<String, Object> check(@RequestBody Map<String, Object> body) {
		requireSuperAdmin();
		String user = String.valueOf(body.get("user")).trim();
		String relation = String.valueOf(body.get("relation")).trim();
		String object = String.valueOf(body.get("object")).trim();
		long t0 = System.nanoTime();
		boolean allowed = authz.check(user, relation, object);
		return Map.of("allowed", allowed, "ms", (System.nanoTime() - t0) / 1_000_000);
	}

	/** Puts tuple changes that gave up back in the queue (after OpenFGA was down for a long time). */
	@PostMapping("/retry")
	public Map<String, Integer> retry() {
		requireSuperAdmin();
		int n = jdbc.sql("update fga_outbox set gave_up_at = null, attempts = 0, next_attempt_at = now() where processed_at is null and gave_up_at is not null").update();
		return Map.of("requeued", n);
	}

	private void requireSuperAdmin() {
		if (!superAdminInDatabase() && !authz.isSuperAdmin()) {
			throw new ForbiddenException("Only a super admin can see the OpenFGA settings");
		}
	}

	private static String mask(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		return id.length() <= 10 ? id : id.substring(0, 6) + "…" + id.substring(id.length() - 4);
	}

	private boolean superAdminInDatabase() {
		UUID employee = currentUser.employeeId();
		if (employee == null) {
			return false;
		}
		return jdbc.sql("""
				select count(*) from user_role ur
				join app_user u on u.id = ur.user_id
				join role r on r.id = ur.role_id
				where u.employee_id = :e and u.org_id = :org and u.status = 'ACTIVE'
				  and r.org_id = :org and r.key = 'super_admin' and ur.scope = 'ORGANIZATION'
				""").param("e", employee).param("org", currentUser.orgId()).query(Long.class).single() > 0;
	}
}
