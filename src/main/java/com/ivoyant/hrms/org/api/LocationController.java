package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.dto.LocationEmployee;
import com.ivoyant.hrms.org.dto.LocationMeta;
import com.ivoyant.hrms.org.dto.LocationOption;
import com.ivoyant.hrms.org.dto.LocationResponse;
import com.ivoyant.hrms.org.dto.LocationSummary;
import com.ivoyant.hrms.org.dto.LocationUsage;
import com.ivoyant.hrms.org.dto.StatutoryPreview;
import com.ivoyant.hrms.org.service.LocationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Settings → Organisation → Locations. Reading the full record needs View on the org module (or HR admin); changing
 * needs Edit. Every member may list the short form and the picker options. Bodies of POST and PATCH are flat JSON
 * (name, code, type, addressLine, city, state, postalCode, country, timezone, phone, email, headEmployeeId,
 * weekPattern, startTime, endTime, graceMinutes, holidayCalendarId, attendanceMethods, geoRadiusM, latitude,
 * longitude, wfhAllowed, lwfApplicable, esiNotified, shopsEstabRegNo, gstin, seatCapacity, assetStore, active);
 * PATCH is a JSON merge patch.
 */
@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final LocationService service;

	public LocationController(LocationService service) {
		this.service = service;
	}

	/** Full cards for admins, a short row for other members. {@code include=stats} adds headcount and utilisation. */
	@GetMapping
	@RequiresMember
	public List<?> list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) String q,
			@RequestParam(required = false) String include) {
		return service.list(active, q, "stats".equalsIgnoreCase(include));
	}

	@GetMapping("/summary")
	@PreAuthorize(VIEW)
	public LocationSummary summary() {
		return service.summary();
	}

	/** Active locations only, for pickers. */
	@GetMapping("/options")
	@RequiresMember
	public List<LocationOption> options() {
		return service.options();
	}

	@GetMapping("/meta")
	@RequiresMember
	public ResponseEntity<LocationMeta> meta() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
				.body(service.meta());
	}

	@GetMapping("/statutory-preview")
	@PreAuthorize(VIEW)
	public StatutoryPreview statutoryPreview(@RequestParam String state, @RequestParam(required = false) UUID locationId) {
		return service.statutoryPreview(state, locationId);
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public LocationResponse get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public LocationResponse create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public LocationResponse update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}

	/** What still points at the location: the numbers behind the delete message. */
	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public LocationUsage usage(@PathVariable UUID id) {
		return service.usage(id);
	}

	/** People based here: admins, and the location head. */
	@GetMapping("/{id}/employees")
	@PreAuthorize(VIEW + " or @authz.can('can_manage', 'location:' + #id)")
	public PageResponse<LocationEmployee> employees(@PathVariable UUID id, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return service.employees(id, status, page, size);
	}
}
