package com.ivoyant.hrms.leave.api;

import com.ivoyant.hrms.leave.ResolvedStep;
import com.ivoyant.hrms.leave.service.ApprovalChainService;
import com.ivoyant.hrms.leave.service.ApprovalChainService.Chain;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The leave approval chain (Settings → Leave &amp; attendance → Approval chain): one chain for the organization, saved whole. */
@RestController
@RequestMapping("/api/v1/leave/approval-chain")
@PreAuthorize("@moduleAccess.has('leave', 'ADMIN')")
public class ApprovalChainController {

	private final ApprovalChainService service;

	public ApprovalChainController(ApprovalChainService service) {
		this.service = service;
	}

	@GetMapping
	public Chain get() {
		return service.get();
	}

	/** {@code {"steps": [{"approver": "REPORTING_MANAGER", "condition": "ALWAYS"}, {"approver": "HR", "condition": "OVER_DAYS", "minDays": 3}]}} with {@code If-Match}. */
	@PutMapping
	public Chain put(@RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.put(body, ifMatch);
	}

	/** {@code {"employeeId": "...", "days": 5, "steps": [...]}} (steps optional: a draft to try). Empty means nobody needs to approve. */
	@PostMapping("/preview")
	public List<ResolvedStep> preview(@RequestBody Map<String, Object> body) {
		return service.preview(body);
	}
}
