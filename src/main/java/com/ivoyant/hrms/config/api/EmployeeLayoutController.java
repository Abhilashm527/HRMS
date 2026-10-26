package com.ivoyant.hrms.config.api;

import com.ivoyant.hrms.config.service.EmployeeLayoutService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings → Configuration → Employee fields: the profile's sections and fields as one saved layout. Owners of the employee profile only. */
@RestController
@RequestMapping("/api/v1/config/employee-fields")
public class EmployeeLayoutController {

	private final EmployeeLayoutService service;

	public EmployeeLayoutController(EmployeeLayoutService service) {
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> get() {
		return service.get();
	}

	/** The whole layout, with {@code If-Match: <version>}. A type change on a field that has values answers 409 CONFIRM_REQUIRED; repeat with {@code ?force=true}. */
	@PutMapping
	public Map<String, Object> save(@RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestParam(defaultValue = "false") boolean force) {
		return service.save(body, ifMatch, force);
	}

	@PostMapping("/restore/{sectionKey}")
	public Map<String, Object> restore(@PathVariable String sectionKey) {
		return service.restore(sectionKey);
	}

	/** Fields created after the role last confirmed (Module permissions "N new fields"). */
	@GetMapping("/new-fields")
	public List<Map<String, Object>> newFields(@RequestParam UUID roleId) {
		return service.newFields(roleId);
	}

	@PostMapping("/new-fields/ack")
	public Map<String, Object> ack(@RequestParam UUID roleId) {
		service.ackNewFields(roleId);
		return Map.of("acked", true);
	}
}
