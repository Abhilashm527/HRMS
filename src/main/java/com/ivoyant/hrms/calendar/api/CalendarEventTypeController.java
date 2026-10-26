package com.ivoyant.hrms.calendar.api;

import com.ivoyant.hrms.calendar.service.CalendarEventTypeService;
import com.ivoyant.hrms.calendar.service.CalendarEventTypeService.AuditEntry;
import com.ivoyant.hrms.calendar.service.CalendarEventTypeService.EventType;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calendar event types (Settings → Configuration → Calendar event types). Managing needs the calendar module's Admin level (HR and
 * super admins always have it); anyone can read the types they may pick from.
 */
@RestController
@RequestMapping("/api/v1/calendar/event-types")
public class CalendarEventTypeController {

	private static final String MANAGE = "@moduleAccess.has('calendar', 'ADMIN')";

	private final CalendarEventTypeService service;

	public CalendarEventTypeController(CalendarEventTypeService service) {
		this.service = service;
	}

	/** {@code includeSystem=true} adds Holiday, Birthday and Work anniversary. */
	@GetMapping
	@PreAuthorize(MANAGE)
	public List<EventType> list(@RequestParam(defaultValue = "true") boolean includeSystem) {
		return service.list(includeSystem);
	}

	/** The types the caller can pick when creating an event. */
	@GetMapping("/options")
	@RequiresMember
	public List<EventType> options() {
		return service.options();
	}

	@GetMapping("/audit")
	@PreAuthorize(MANAGE)
	public List<AuditEntry> audit(@RequestParam(defaultValue = "25") int limit) {
		return service.audit(limit);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(MANAGE)
	public EventType create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(MANAGE)
	public EventType update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
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
	public List<EventType> order(@RequestBody List<UUID> ids) {
		return service.reorder(ids);
	}
}
