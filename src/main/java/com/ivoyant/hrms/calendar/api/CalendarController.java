package com.ivoyant.hrms.calendar.api;

import com.ivoyant.hrms.calendar.service.CalendarEventService;
import com.ivoyant.hrms.calendar.service.CalendarFeedService;
import com.ivoyant.hrms.calendar.service.CalendarViewers;
import com.ivoyant.hrms.common.authz.RequiresMember;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * The Calendar menu: the feed for a date range (events plus holidays, birthdays, anniversaries and leave), one event, adding and
 * changing events, HR approval, RSVPs, .ics downloads, my calendar settings and the private subscription link, and meeting rooms.
 * What each person may see and do is decided per layer in the service.
 */
@RestController
@RequestMapping("/api/v1/calendar")
@RequiresMember
public class CalendarController {

	private final CalendarFeedService feed;
	private final CalendarEventService events;
	private final CalendarViewers viewers;

	public CalendarController(CalendarFeedService feed, CalendarEventService events, CalendarViewers viewers) {
		this.feed = feed;
		this.events = events;
		this.viewers = viewers;
	}

	@GetMapping("/feed")
	public Map<String, Object> feed(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return feed.feed(from, to);
	}

	@GetMapping("/options")
	public Map<String, Object> options() {
		return events.options();
	}

	@GetMapping("/events/{id}")
	public Map<String, Object> event(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return feed.detail(id, date);
	}

	/** Errors and warnings for a form before saving; {@code editId} when editing. */
	@PostMapping("/events/check")
	public Map<String, Object> check(@RequestBody Map<String, Object> body) {
		return events.check(body);
	}

	@PostMapping("/events")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody Map<String, Object> body) {
		return events.create(body);
	}

	@PatchMapping("/events/{id}")
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return events.update(id, body, ifMatch);
	}

	@DeleteMapping("/events/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		events.delete(id);
	}

	@PostMapping("/events/{id}/approve")
	public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return events.decide(id, true, body == null ? null : (String) body.get("note"));
	}

	@PostMapping("/events/{id}/reject")
	public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return events.decide(id, false, body == null ? null : (String) body.get("note"));
	}

	/** Body: {@code answer} — YES, MAYBE or NO. */
	@PutMapping("/events/{id}/rsvp")
	public Map<String, Object> rsvp(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return events.rsvp(id, body == null ? null : String.valueOf(body.get("answer")));
	}

	@GetMapping(value = "/events/{id}/ics", produces = "text/calendar")
	public ResponseEntity<byte[]> eventIcs(@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return file("event.ics", feed.icsOne(id, date));
	}

	@GetMapping(value = "/export.ics", produces = "text/calendar")
	public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return file("calendar-" + from + ".ics", feed.ics(from, to));
	}

	static ResponseEntity<byte[]> file(String name, String body) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
				.body(body.getBytes(StandardCharsets.UTF_8));
	}

	@GetMapping("/settings")
	public Map<String, Object> settings() {
		return viewers.mySettings();
	}

	/** Merge patch: {@code bdayOptOut, remind, timeZone}. */
	@PatchMapping("/settings")
	public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body) {
		return viewers.updateSettings(body);
	}

	/** A new private subscription link (shown once; any earlier link stops working). */
	@PostMapping("/settings/subscription")
	public Map<String, Object> subscribe() {
		return viewers.newSubscription();
	}

	@DeleteMapping("/settings/subscription")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe() {
		viewers.revokeSubscription();
	}

	/** Active rooms; {@code all=true} (calendar admins) adds switched-off ones. */
	@GetMapping("/rooms")
	public List<Map<String, Object>> rooms(@RequestParam(defaultValue = "false") boolean all) {
		return events.rooms(all);
	}

	@PostMapping("/rooms")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> addRoom(@RequestBody Map<String, Object> body) {
		return events.saveRoom(null, body);
	}

	@PatchMapping("/rooms/{id}")
	public Map<String, Object> updateRoom(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return events.saveRoom(id, body);
	}

	@DeleteMapping("/rooms/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRoom(@PathVariable UUID id) {
		events.deleteRoom(id);
	}
}
