package com.ivoyant.hrms.onboarding.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.onboarding.service.EssOnboardingService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The employee's own first-90-days checklist. */
@RestController
@RequestMapping("/api/v1/me/onboarding")
public class MeOnboardingController {

	private final EssOnboardingService service;

	public MeOnboardingController(EssOnboardingService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresMember
	public Map<String, Object> get() {
		return service.get();
	}

	@PatchMapping("/{key}")
	@RequiresMember
	public Map<String, Object> set(@PathVariable String key, @RequestBody Map<String, Boolean> body) {
		return service.set(key, Boolean.TRUE.equals(body.get("done")));
	}
}
