package com.ivoyant.hrms.notification.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own in-app notifications. */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public NotificationController(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@GetMapping
	@RequiresMember
	public List<Map<String, Object>> list(@RequestParam(required = false) String type, @RequestParam(defaultValue = "true") boolean unread,
			@RequestParam(defaultValue = "50") int limit) {
		return jdbc.sql("""
				select id, type, title, body, link, created_at, read_at from notification_inbox
				where employee_id = :e and (cast(:t as text) is null or type = :t) and (not :u or read_at is null)
				order by created_at desc limit :n""").param("e", currentUser.requireEmployeeId()).param("t", type).param("u", unread)
				.param("n", Math.min(Math.max(limit, 1), 200)).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getLong(1));
					m.put("type", rs.getString(2));
					m.put("title", rs.getString(3));
					m.put("body", rs.getString(4));
					m.put("link", rs.getString(5));
					m.put("at", rs.getObject(6, OffsetDateTime.class));
					m.put("read", rs.getObject(7) != null);
					return m;
				}).list();
	}

	/** {@code {"type": "ASSET_ALERT"}} marks that kind read; with {@code ids} only those; with neither, everything. */
	@PostMapping("/read")
	@RequiresMember
	@SuppressWarnings("unchecked")
	public Map<String, Object> read(@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> b = body == null ? Map.of() : body;
		List<Number> ids = b.get("ids") instanceof List<?> l ? (List<Number>) l : null;
		int n = jdbc.sql("""
				update notification_inbox set read_at = now() where employee_id = :e and read_at is null and (cast(:t as text) is null or type = :t)
				and (:all or id in (:ids))""").param("e", currentUser.requireEmployeeId()).param("t", b.get("type") == null ? null : b.get("type").toString())
				.param("all", ids == null).param("ids", ids == null || ids.isEmpty() ? List.of(-1L) : ids.stream().map(Number::longValue).toList()).update();
		return Map.of("marked", n);
	}
}
