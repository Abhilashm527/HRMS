package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.security.CurrentUser;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** The directory view settings (view, fields, photos, group, sort, density) so they follow the person across browsers. */
@RestController
@RequestMapping("/api/v1/me/preferences")
public class PreferenceController {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ObjectMapper json;

	public PreferenceController(JdbcClient jdbc, CurrentUser currentUser, ObjectMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.json = json;
	}

	@GetMapping("/directory")
	@RequiresMember
	@SuppressWarnings("unchecked")
	public Map<String, Object> get() {
		return jdbc.sql("select value::text from user_preference where employee_id = :e and key = 'directory'").param("e", currentUser.requireEmployeeId()).query(String.class)
				.optional().map(s -> (Map<String, Object>) json.readValue(s, Map.class)).orElse(Map.of());
	}

	@PutMapping("/directory")
	@RequiresMember
	public Map<String, Object> put(@RequestBody Map<String, Object> body) {
		jdbc.sql("""
				insert into user_preference (employee_id, key, value) values (:e, 'directory', :v::jsonb)
				on conflict (employee_id, key) do update set value = :v::jsonb, updated_at = now()""").param("e", currentUser.requireEmployeeId())
				.param("v", json.writeValueAsString(body)).update();
		return body;
	}
}
