package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.org.dto.EmploymentTypeDtos;
import com.ivoyant.hrms.org.service.EmploymentTypeService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employment types. Reading everything needs View on the org module (members get a short picker form); changing needs Edit.
 * Bodies of POST and PATCH: name, code (2–4 letters), probationMonths (0–24), noticeDays (0–180), leaveEligible,
 * payrollEligible, fixedTerm (people need a contract end date), sort, active.
 */
@RestController
@RequestMapping("/api/v1/employment-types")
public class EmploymentTypeController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final EmploymentTypeService service;

	public EmploymentTypeController(EmploymentTypeService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) String include) {
		return service.list(active, "stats".equalsIgnoreCase(include));
	}

	@GetMapping("/options")
	@RequiresMember
	public List<EmploymentTypeDtos.Option> options() {
		return service.options();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public EmploymentTypeDtos.Node get(@PathVariable UUID id) {
		return service.detail(id);
	}

	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public EmploymentTypeDtos.Usage usage(@PathVariable UUID id) {
		return service.usage(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public EmploymentTypeDtos.Node create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public EmploymentTypeDtos.Node update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** Hard delete, only when nothing points at it; {@code reassignTo} first moves the current employees. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID reassignTo) {
		service.delete(id, reassignTo);
	}
}
