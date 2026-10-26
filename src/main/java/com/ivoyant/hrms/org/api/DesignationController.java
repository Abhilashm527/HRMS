package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.BandCheck;
import com.ivoyant.hrms.org.dto.LadderHealth;
import com.ivoyant.hrms.org.dto.PositionBasic;
import com.ivoyant.hrms.org.dto.PositionEmployee;
import com.ivoyant.hrms.org.dto.PositionNode;
import com.ivoyant.hrms.org.dto.PositionOption;
import com.ivoyant.hrms.org.dto.PositionSummary;
import com.ivoyant.hrms.org.dto.PositionUsage;
import com.ivoyant.hrms.org.dto.SuggestedManager;
import com.ivoyant.hrms.org.service.DesignationService;
import com.ivoyant.hrms.org.service.PositionQueryService;
import java.math.BigDecimal;
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
 * Positions (the UI's word for designations; both paths work). Reading everything needs View on the org module (members
 * get a short form and the picker options); changing needs Edit. Bodies of POST and PATCH are flat JSON: name, grade,
 * level, track, bandMin, bandMax (full yearly amounts), reportsToId, jobLevelId, minExp, approves, active, description,
 * and {@code custom} (values of the position's custom fields). PATCH is a JSON merge patch: an explicit null clears the
 * grade, the band (both ends), the reports-to, the job level or the minimum experience.
 */
@RestController
@RequestMapping({"/api/v1/designations", "/api/v1/positions"})
public class DesignationController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final DesignationService service;
	private final PositionQueryService query;

	public DesignationController(DesignationService service, PositionQueryService query) {
		this.service = service;
		this.query = query;
	}

	/**
	 * {@code view=flat} (default) lists positions most senior first; {@code view=ladder} nests them under their
	 * reports-to. {@code include=stats} adds headcounts, title counts and direct reports.
	 */
	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) String q,
			@RequestParam(required = false) String track, @RequestParam(defaultValue = "flat") String view,
			@RequestParam(required = false) String include, @RequestParam(required = false) String sort) {
		return query.list(active, q, track, view, "stats".equalsIgnoreCase(include), sort);
	}

	@GetMapping("/summary")
	@PreAuthorize(VIEW)
	public PositionSummary summary() {
		return query.summary();
	}

	/** Active positions, most senior first, for pickers. */
	@GetMapping("/options")
	@RequiresMember
	public List<PositionOption> options(@RequestParam(required = false) UUID departmentId) {
		return query.options(departmentId);
	}

	/** Whole-ladder health for the banners: loops, several tops, level inversions, no band, overlaps. */
	@GetMapping("/validate")
	@PreAuthorize(VIEW)
	public LadderHealth validate() {
		return query.validate();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public PositionNode get(@PathVariable UUID id) {
		return query.get(id);
	}

	/** From the top of the ladder down to this position. */
	@GetMapping("/{id}/ladder")
	@RequiresMember
	public List<PositionBasic> ladder(@PathVariable UUID id) {
		return query.ladder(id);
	}

	@GetMapping("/{id}/employees")
	@PreAuthorize(VIEW)
	public PageResponse<PositionEmployee> employees(@PathVariable UUID id, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return query.employees(id, status, page, size);
	}

	/** What would block deleting or deactivating the position, with the message to show. */
	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public PositionUsage usage(@PathVariable UUID id) {
		return query.usage(id);
	}

	/** Active employees in the position this one usually reports to, best match for the department and location first. */
	@GetMapping("/{id}/suggest-manager")
	@PreAuthorize(VIEW)
	public List<SuggestedManager> suggestManager(@PathVariable UUID id, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId) {
		return query.suggestManager(id, departmentId, locationId);
	}

	/** {@code {"amount": 3200000}} (a full yearly amount) against the position's band. */
	@PostMapping("/{id}/band-check")
	@PreAuthorize(VIEW)
	public BandCheck bandCheck(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		Object amount = body.get("amount");
		if (amount == null) {
			throw new com.ivoyant.hrms.common.web.BadRequestException("amount is required");
		}
		try {
			return query.bandCheck(id, new BigDecimal(amount.toString()));
		}
		catch (NumberFormatException ex) {
			throw new com.ivoyant.hrms.common.web.BadRequestException("amount must be a number");
		}
	}

	/** Makes the position the top of the ladder; the old top now reports to it. */
	@PostMapping("/top")
	@PreAuthorize(EDIT)
	public PositionNode top(@RequestBody Map<String, Object> body) {
		return service.setTop(UUID.fromString(String.valueOf(body.get("id"))));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public PositionNode create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public PositionNode update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** Hard delete, only when nothing points at it; {@code reassignTo} first moves people, titles and reporting positions. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID reassignTo) {
		service.delete(id, reassignTo);
	}
}
