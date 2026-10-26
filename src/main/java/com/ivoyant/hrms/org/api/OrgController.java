package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.org.dto.OrgMeta;
import com.ivoyant.hrms.org.dto.OrgResponse;
import com.ivoyant.hrms.org.service.OrgService;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Organisation → Organization. Single organization: it is always the caller's. There is no Save button,
 * each field saves on its own, so PATCH takes any subset (JSON merge patch). Time zone, currency and financial year
 * also accept the UI labels ("Asia/Kolkata (IST)", "INR (₹)", "April – March").
 */
@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

	private final OrgService service;

	public OrgController(OrgService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresMember
	public OrgResponse get() {
		return service.get();
	}

	@PatchMapping(consumes = {"application/json", "application/merge-patch+json"})
	@RequiresHrAdmin
	public OrgResponse patch(@RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.patch(patch, ifMatch);
	}

	@GetMapping("/meta")
	@RequiresMember
	public ResponseEntity<OrgMeta> meta() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).body(service.meta());
	}
}
