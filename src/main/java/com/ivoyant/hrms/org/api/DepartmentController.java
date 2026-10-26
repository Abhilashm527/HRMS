package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.dto.DepartmentBasic;
import com.ivoyant.hrms.org.dto.DepartmentEmployee;
import com.ivoyant.hrms.org.dto.DepartmentNode;
import com.ivoyant.hrms.org.dto.DepartmentOption;
import com.ivoyant.hrms.org.dto.DepartmentSummary;
import com.ivoyant.hrms.org.dto.DepartmentUsage;
import com.ivoyant.hrms.org.dto.TreeHealth;
import com.ivoyant.hrms.org.service.DepartmentQueryService;
import com.ivoyant.hrms.org.service.DepartmentService;
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
 * Departments. Reading everything needs View on the org module (members get a short form and the picker options);
 * changing needs Edit. Bodies of POST and PATCH are flat JSON: name, code, parentId, headEmployeeId, costCentre,
 * defaultLocationId, active, description, sort, and {@code custom} (values of the department's custom fields).
 * PATCH is a JSON merge patch: an explicit null clears the head, parent, cost centre, location or description.
 */
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final DepartmentService service;
	private final DepartmentQueryService query;

	public DepartmentController(DepartmentService service, DepartmentQueryService query) {
		this.service = service;
		this.query = query;
	}

	/**
	 * {@code view=flat} (default) lists departments in tree order with their path and level; {@code view=tree} nests
	 * them under {@code roots}. {@code include=stats} adds headcounts and child counts.
	 */
	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) String q,
			@RequestParam(required = false) UUID parentId, @RequestParam(defaultValue = "flat") String view,
			@RequestParam(required = false) String include) {
		return query.list(active, q, parentId, view, "stats".equalsIgnoreCase(include));
	}

	@GetMapping("/summary")
	@PreAuthorize(VIEW)
	public DepartmentSummary summary() {
		return query.summary();
	}

	/** Active departments with their path, for pickers. */
	@GetMapping("/options")
	@RequiresMember
	public List<DepartmentOption> options() {
		return query.options();
	}

	/** Whole-tree health for the hierarchy banners: loops, over-deep levels, no head, inactive parents. */
	@GetMapping("/validate")
	@PreAuthorize(VIEW)
	public TreeHealth validate() {
		return query.validate();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public DepartmentNode get(@PathVariable UUID id) {
		return query.get(id);
	}

	/** Ancestors from the root down to the department. */
	@GetMapping("/{id}/path")
	@RequiresMember
	public List<DepartmentBasic> path(@PathVariable UUID id) {
		return query.path(id);
	}

	/** The department and everything below it. */
	@GetMapping("/{id}/descendants")
	@RequiresMember
	public List<DepartmentBasic> descendants(@PathVariable UUID id) {
		return query.descendants(id);
	}

	@GetMapping("/{id}/employees")
	@PreAuthorize(VIEW)
	public PageResponse<DepartmentEmployee> employees(@PathVariable UUID id,
			@RequestParam(defaultValue = "false") boolean includeChildren, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return query.employees(id, includeChildren, status, page, size);
	}

	/** What would block deleting or deactivating the department, with the message to show. */
	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public DepartmentUsage usage(@PathVariable UUID id) {
		return query.usage(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public DepartmentNode create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public DepartmentNode update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** Drag-and-drop: {@code {"parentId": "...", "sort": 20}}; a null parentId makes it a root. */
	@PostMapping("/{id}/move")
	@PreAuthorize(EDIT)
	public DepartmentNode move(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		Object parent = body.get("parentId");
		Object sort = body.get("sort");
		return service.move(id, parent == null || parent.toString().isBlank() ? null : UUID.fromString(parent.toString()),
				sort == null ? null : Integer.valueOf(sort.toString()));
	}

	/** Hard delete, only when nothing points at it; {@code reassignTo} first moves people, titles, teams and sub-departments. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID reassignTo) {
		service.delete(id, reassignTo);
	}
}
