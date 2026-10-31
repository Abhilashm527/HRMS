package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.service.CalendarFeedService.Ev;
import com.ivoyant.hrms.notification.Notifier;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends event reminders (15 minutes, 1 hour or 1 day before) as in-app messages to everyone the event is for. An all-day event counts
 * as starting at 09:00. Each occurrence is reminded once; editing an event clears its upcoming reminders so the new time is used.
 */
@Component
class CalendarReminders {

	private static final Logger log = LoggerFactory.getLogger(CalendarReminders.class);
	/** A reminder that was due within this window is still sent (covers a restart or a slow run). */
	private static final Duration GRACE = Duration.ofMinutes(20);

	private final JdbcClient jdbc;
	private final CalendarFeedService feed;
	private final CalendarViewers viewers;
	private final Notifier notifier;

	CalendarReminders(JdbcClient jdbc, CalendarFeedService feed, CalendarViewers viewers, Notifier notifier) {
		this.jdbc = jdbc;
		this.feed = feed;
		this.viewers = viewers;
		this.notifier = notifier;
	}

	@Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
	@Transactional
	public void sendDue() {
		List<UUID> orgs = jdbc.sql("select distinct org_id from calendar_event where remind <> 'NONE' and status = 'LIVE'").query(UUID.class).list();
		for (UUID org : orgs) {
			ZoneId orgZone = viewers.orgZone(org);
			ZonedDateTime now = ZonedDateTime.now(orgZone);
			LocalDate today = now.toLocalDate();
			for (Ev e : feed.overlapping(org, today.minusDays(1), today.plusDays(2))) {
				if ("NONE".equals(e.remind()) || !"LIVE".equals(e.status())) {
					continue;
				}
				ZoneId z = feed.zoneOf(e, orgZone);
				for (LocalDate d : CalendarFeedService.occurrences(e, today.minusDays(1), today.plusDays(2))) {
					ZonedDateTime starts = ZonedDateTime.of(d, e.start() != null ? e.start() : LocalTime.of(9, 0), z);
					ZonedDateTime due = starts.minus(switch (e.remind()) {
						case "15M" -> Duration.ofMinutes(15);
						case "1H" -> Duration.ofHours(1);
						default -> Duration.ofDays(1);
					});
					if (due.isAfter(now) || due.isBefore(now.minus(GRACE)) || !starts.isAfter(now.minus(GRACE))) {
						continue;
					}
					int fresh = jdbc.sql("insert into calendar_reminder_sent (event_id, occurs_on) values (:e, :d) on conflict do nothing")
							.param("e", e.id()).param("d", d).update();
					if (fresh == 0) {
						continue;
					}
					try {
						List<UUID> to = feed.invitees(org, e);
						String when = e.start() == null ? "All day" : "Starts at " + e.start() + (e.end() != null ? "–" + e.end() : "");
						notifier.inApp(to, "CALENDAR", "Reminder: " + (e.sensitive() ? "calendar event" : e.title()),
								when + (d.equals(today) ? " today" : " · " + CalendarEventService.DAY_FMT.format(d))
										+ (e.where() != null && !e.sensitive() ? " · " + e.where() : ""),
								"/calendar?event=" + e.id() + "&date=" + d);
					}
					catch (RuntimeException ex) {
						log.warn("Calendar reminder for {} could not be sent: {}", e.id(), ex.getMessage());
					}
				}
			}
		}
	}
}
