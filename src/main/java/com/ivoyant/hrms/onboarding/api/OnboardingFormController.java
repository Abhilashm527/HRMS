package com.ivoyant.hrms.onboarding.api;

import com.ivoyant.hrms.onboarding.service.HireService;
import com.ivoyant.hrms.onboarding.service.InviteService;
import com.ivoyant.hrms.onboarding.service.M365Service;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the New hire drawer needs: the configured form, work-email checks and the invite preview. */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingFormController {

	private static final String VIEW = "@moduleAccess.has('recruiting', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('recruiting', 'EDIT')";

	private final HireService hires;
	private final M365Service m365;
	private final InviteService invites;

	public OnboardingFormController(HireService hires, M365Service m365, InviteService invites) {
		this.hires = hires;
		this.m365 = m365;
		this.invites = invites;
	}

	@GetMapping("/hire-form")
	@PreAuthorize(EDIT)
	public Map<String, Object> form(@RequestParam(required = false) UUID employmentTypeId) {
		return hires.form(employmentTypeId);
	}

	@GetMapping("/upn-check")
	@PreAuthorize(EDIT)
	public Map<String, Object> check(@RequestParam String user, @RequestParam(required = false) UUID hireId) {
		return m365.check(user, hireId);
	}

	@GetMapping("/upn-suggest")
	@PreAuthorize(EDIT)
	public Map<String, Object> suggest(@RequestParam(defaultValue = "") String first, @RequestParam(defaultValue = "") String last,
			@RequestParam(required = false) UUID employmentTypeId) {
		return m365.suggest(first, last, employmentTypeId);
	}

	@PostMapping("/invite-preview")
	@PreAuthorize(EDIT)
	public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
		return invites.preview(body);
	}
}
