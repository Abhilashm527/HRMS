package com.ivoyant.hrms.calendar.api;

import com.ivoyant.hrms.calendar.service.HolidayTypeService;
import com.ivoyant.hrms.calendar.service.HolidayTypeService.HolidayType;
import com.ivoyant.hrms.calendar.service.HolidayTypeService.Saved;
import com.ivoyant.hrms.common.authz.RequiresMember;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Holiday types. Everyone reads them (calendar chips); changing needs Admin on the calendar or the leave module (HR and super admins always have it). */
@RestController
@RequestMapping("/api/v1/holidays/types")
public class HolidayTypeController {

	private static final String MANAGE = "@moduleAccess.has('calendar', 'ADMIN') or @moduleAccess.has('leave', 'ADMIN')";

	private final HolidayTypeService service;

	public HolidayTypeController(HolidayTypeService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresMember
	public List<HolidayType> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(MANAGE)
	public HolidayType create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(MANAGE)
	public Saved update(@PathVariable UUID id, @RequestBody Map<String, Object> patch) {
		return service.update(id, patch);
	}

	/** {@code moveTo} defaults to the first other type. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(MANAGE)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID moveTo) {
		service.delete(id, moveTo);
	}
}
