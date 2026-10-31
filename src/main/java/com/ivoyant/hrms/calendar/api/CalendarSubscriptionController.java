package com.ivoyant.hrms.calendar.api;

import com.ivoyant.hrms.calendar.service.CalendarFeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The private calendar subscription link that Google Calendar, Outlook or Apple Calendar poll. It carries no sign-in: the random token
 * in the link is the credential (only its hash is stored), so it is open in the security configuration like the new-hire portal.
 */
@RestController
public class CalendarSubscriptionController {

	private final CalendarFeedService feed;

	public CalendarSubscriptionController(CalendarFeedService feed) {
		this.feed = feed;
	}

	@GetMapping(value = "/api/v1/calendar/ical/{token}.ics", produces = "text/calendar")
	public ResponseEntity<byte[]> ical(@PathVariable String token) {
		return CalendarController.file("ihrms.ics", feed.subscription(token));
	}
}
