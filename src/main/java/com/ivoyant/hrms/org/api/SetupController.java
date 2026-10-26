package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresSuperAdmin;
import com.ivoyant.hrms.org.dto.SetupResponse;
import com.ivoyant.hrms.org.service.SetupChecklistService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Setup checklist: 12 steps worked out from the data, plus the steps an admin confirms by hand. */
@RestController
@RequestMapping("/api/v1/org/setup")
public class SetupController {

	private final SetupChecklistService service;

	public SetupController(SetupChecklistService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresHrAdmin
	public SetupResponse get() {
		return service.get();
	}

	/** Marks a step reviewed: org_cfg, roles, ep or fls. */
	@PostMapping("/{key}/ack")
	@RequiresHrAdmin
	public SetupResponse ack(@PathVariable String key) {
		return service.ack(key);
	}

	@DeleteMapping("/{key}/ack")
	@RequiresSuperAdmin
	public SetupResponse unack(@PathVariable String key) {
		return service.unack(key);
	}
}
