package com.ivoyant.hrms.requests.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.requests.service.RequestTypeService;
import com.ivoyant.hrms.requests.service.RequestTypeService.Listing;
import com.ivoyant.hrms.requests.service.RequestTypeService.RequestType;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Request types (letters, reimbursements, help-desk). Managing needs the requests module's Admin level; everyone can read the shown types. */
@RestController
@RequestMapping("/api/v1/request-types")
public class RequestTypeController {

	private static final String MANAGE = "@moduleAccess.has('requests', 'ADMIN')";

	private final RequestTypeService service;

	public RequestTypeController(RequestTypeService service) {
		this.service = service;
	}

	/** Every type with its usage, plus how many are shown per category. */
	@GetMapping
	@PreAuthorize(MANAGE)
	public Listing list(@RequestParam(required = false) String category) {
		return service.list(category);
	}

	/** The shown types: what an employee picks from. */
	@GetMapping("/options")
	@RequiresMember
	public List<RequestType> options(@RequestParam(required = false) String category) {
		return service.options(category);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(MANAGE)
	public RequestType create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(MANAGE)
	public RequestType update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(MANAGE)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}

	@PutMapping("/order")
	@PreAuthorize(MANAGE)
	public Listing order(@RequestParam String category, @RequestBody List<UUID> ids) {
		return service.reorder(category, ids);
	}
}
