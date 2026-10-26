package com.ivoyant.hrms.config.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.config.dto.ChangeRequestResponse;
import com.ivoyant.hrms.config.service.ChangeRequestService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Change requests of one employee's profile, as the profile screen shows them. Approving or rejecting follows the area's approver rules. */
@RestController
@RequestMapping("/api/v1/employees/{id}/change-requests")
public class ProfileChangeRequestController {

	private static final String AREA = "employee_profile";

	private final ChangeRequestService service;

	public ProfileChangeRequestController(ChangeRequestService service) {
		this.service = service;
	}

	/** The ones the caller can decide, plus their own. */
	@GetMapping
	@RequiresMember
	public List<Map<String, Object>> list(@PathVariable UUID id, @RequestParam(defaultValue = "PENDING") String status) {
		List<ChangeRequestResponse> decidable = service.list(status, AREA, false).stream().filter(r -> r.recordId().equals(id)).toList();
		List<ChangeRequestResponse> mine = service.list(status, AREA, true).stream().filter(r -> r.recordId().equals(id)).toList();
		List<Map<String, Object>> out = new ArrayList<>();
		for (ChangeRequestResponse r : decidable) {
			out.add(row(r, true));
		}
		for (ChangeRequestResponse r : mine) {
			if (decidable.stream().noneMatch(d -> d.id().equals(r.id()))) {
				out.add(row(r, false));
			}
		}
		return out;
	}

	@PostMapping("/{reqId}/approve")
	@RequiresMember
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void approve(@PathVariable UUID id, @PathVariable UUID reqId) {
		requireDecidable(id, reqId);
		service.approve(reqId);
	}

	@PostMapping("/{reqId}/reject")
	@RequiresMember
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reject(@PathVariable UUID id, @PathVariable UUID reqId, @RequestBody Map<String, String> body) {
		String reason = body.get("reason");
		if (reason == null || reason.isBlank()) {
			throw new com.ivoyant.hrms.common.web.UnprocessableException(List.of(com.ivoyant.hrms.common.web.ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason")));
		}
		requireDecidable(id, reqId);
		service.reject(reqId, reason.trim());
	}

	private void requireDecidable(UUID employeeId, UUID reqId) {
		if (service.list("PENDING", AREA, false).stream().noneMatch(r -> r.id().equals(reqId) && r.recordId().equals(employeeId))) {
			throw new NotFoundException("No open change request " + reqId + " on this profile that you can decide");
		}
	}

	private static Map<String, Object> row(ChangeRequestResponse r, boolean canApprove) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.id());
		m.put("key", r.fieldKey());
		m.put("label", r.fieldLabel());
		m.put("from", r.oldValue());
		m.put("to", r.newValue());
		m.put("by", r.requestedBy());
		m.put("at", r.requestedAt());
		m.put("status", r.status());
		m.put("note", r.note());
		m.put("canApprove", canApprove);
		return m;
	}
}
