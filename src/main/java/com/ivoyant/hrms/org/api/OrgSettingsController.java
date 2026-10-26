package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.org.dto.SettingsMeta;
import com.ivoyant.hrms.org.service.OrgSettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 22 organization settings. PATCH takes any subset and is all-or-nothing: a setting that contradicts existing data
 * (for example a nesting limit below the deepest department) is refused with 409 IN_USE and the list of what is in the way.
 */
@RestController
@RequestMapping("/api/v1/org")
@RequiresHrAdmin
public class OrgSettingsController {

	private final OrgSettingsService service;

	public OrgSettingsController(OrgSettingsService service) {
		this.service = service;
	}

	@GetMapping("/settings")
	public Map<String, Object> get() {
		return service.get();
	}

	@PatchMapping(value = "/settings", consumes = {"application/json", "application/merge-patch+json"})
	public Map<String, Object> patch(@RequestBody Map<String, Object> patch,
			@RequestParam(defaultValue = "false") boolean force,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.patch(patch, force, ifMatch);
	}

	@GetMapping("/settings/meta")
	public SettingsMeta meta() {
		return service.meta();
	}

	@GetMapping("/employee-id/preview")
	public Map<String, String> employeeIdPreview(@RequestParam String pattern) {
		return service.employeeIdPreview(pattern);
	}

	@GetMapping("/title-format/preview")
	public Map<String, String> titleFormatPreview(@RequestParam String pattern) {
		return service.titleFormatPreview(pattern);
	}
}
