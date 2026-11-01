package com.ivoyant.hrms.iam.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.authz.RequiresSuperAdmin;
import com.ivoyant.hrms.iam.service.AccessAuditService;
import com.ivoyant.hrms.iam.service.NavAccessService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Module permissions (menus, profile tabs and settings pages per role) and the access audit log. */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

	private final NavAccessService nav;
	private final AccessAuditService audit;

	public AccessController(NavAccessService nav, AccessAuditService audit) {
		this.nav = nav;
		this.audit = audit;
	}

	/** The caller's own menus; the app hides everything not listed. */
	@GetMapping("/nav/me")
	@RequiresMember
	public Map<String, Object> mine() {
		return nav.mine();
	}

	@GetMapping("/nav/roles/{roleId}")
	@RequiresHrAdmin
	public Map<String, Object> ofRole(@PathVariable UUID roleId) {
		return nav.ofRole(roleId);
	}

	/** Body: {@code {area: menu|tab|page, key, value}}, or {@code {area: page, keys: [...], value}} for several pages. */
	@PutMapping("/nav/roles/{roleId}")
	@RequiresSuperAdmin
	public Map<String, Object> set(@PathVariable UUID roleId, @RequestBody Map<String, Object> body) {
		String area = str(body.get("area"));
		String value = str(body.get("value"));
		if (body.get("keys") instanceof List<?> keys) {
			return nav.setPages(roleId, keys.stream().map(String::valueOf).toList(), value);
		}
		String label = str(body.get("label"));
		return nav.set(roleId, area, str(body.get("key")), value, label == null || label.length() > 80 ? null : label);
	}

	@GetMapping("/audit")
	@RequiresHrAdmin
	public Map<String, Object> audit(@RequestParam(required = false) String kind, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return audit.page(kind, q, page, Math.max(1, Math.min(size, 200)));
	}

	@GetMapping("/audit/export")
	@RequiresHrAdmin
	public ResponseEntity<byte[]> export(@RequestParam(required = false) String kind, @RequestParam(required = false) String q) {
		byte[] body = ("﻿" + audit.csv(kind, q)).getBytes(StandardCharsets.UTF_8);
		return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"access-audit-" + LocalDate.now() + ".csv\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
