package com.ivoyant.hrms.org.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ivoyant.hrms.calendar.HolidayCalendars;
import com.ivoyant.hrms.common.event.LocationActivationEvent;
import com.ivoyant.hrms.common.event.LocationCreatedEvent;
import com.ivoyant.hrms.common.event.LocationDeletedEvent;
import com.ivoyant.hrms.common.event.LocationPolicyChangedEvent;
import com.ivoyant.hrms.common.event.LocationUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.AttendanceMethod;
import com.ivoyant.hrms.org.AttendancePolicy;
import com.ivoyant.hrms.org.LocationType;
import com.ivoyant.hrms.org.LocationView;
import com.ivoyant.hrms.org.Locations;
import com.ivoyant.hrms.org.StatutoryProfile;
import com.ivoyant.hrms.org.WeekPattern;
import com.ivoyant.hrms.org.WorkSchedule;
import com.ivoyant.hrms.org.domain.Location;
import com.ivoyant.hrms.org.repo.LocationRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The facade other modules use; cached for five minutes and cleared whenever a location changes. */
@Service
class LocationsService implements Locations {

	private final LocationRepository repo;
	private final HolidayCalendars calendars;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Cache<UUID, Optional<Location>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofMinutes(5)).maximumSize(1_000).build();

	LocationsService(LocationRepository repo, HolidayCalendars calendars, JdbcClient jdbc, CurrentUser currentUser) {
		this.repo = repo;
		this.calendars = calendars;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@EventListener
	void onCreated(LocationCreatedEvent e) {
		cache.invalidateAll();
	}

	@EventListener
	void onUpdated(LocationUpdatedEvent e) {
		cache.invalidateAll();
	}

	@EventListener
	void onPolicy(LocationPolicyChangedEvent e) {
		cache.invalidateAll();
	}

	@EventListener
	void onActivation(LocationActivationEvent e) {
		cache.invalidateAll();
	}

	@EventListener
	void onDeleted(LocationDeletedEvent e) {
		cache.invalidateAll();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<LocationView> get(UUID locationId) {
		return load(locationId).map(LocationsService::view);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(UUID locationId) {
		return load(locationId).map(Location::isActive).orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public WorkSchedule scheduleFor(UUID locationId) {
		return load(locationId).map(l -> new WorkSchedule(l.getWeekPattern(), l.getStartTime(), l.getEndTime(),
				l.getGraceMinutes(), zone(l.getTimezone()))).orElseGet(this::orgSchedule);
	}

	@Override
	@Transactional(readOnly = true)
	public WorkSchedule scheduleForEmployee(UUID employeeId) {
		UUID location = locationOf(employeeId);
		return location == null ? orgSchedule() : scheduleFor(location);
	}

	@Override
	@Transactional(readOnly = true)
	public ZoneId zoneForEmployee(UUID employeeId) {
		return scheduleForEmployee(employeeId).zone();
	}

	@Override
	@Transactional(readOnly = true)
	public UUID holidayCalendarFor(UUID locationId) {
		return load(locationId).map(Location::getHolidayCalendarId).orElse(null);
	}

	@Override
	@Transactional(readOnly = true)
	public StatutoryProfile statutoryFor(UUID locationId) {
		Location l = load(locationId).orElseThrow(() -> new IllegalArgumentException("Unknown location " + locationId));
		return new StatutoryProfile(l.getState(), l.isLwfApplicable(), l.isEsiNotified(), l.getGstin(),
				l.getShopsEstabRegNo());
	}

	@Override
	@Transactional(readOnly = true)
	public AttendancePolicy attendanceFor(UUID locationId) {
		Location l = load(locationId).orElseThrow(() -> new IllegalArgumentException("Unknown location " + locationId));
		Set<AttendanceMethod> methods = l.getAttendanceMethods().isEmpty() ? EnumSet.of(AttendanceMethod.WEB)
				: l.getAttendanceMethods().stream().map(AttendanceMethod::valueOf)
						.collect(Collectors.toCollection(() -> EnumSet.noneOf(AttendanceMethod.class)));
		return new AttendancePolicy(methods, l.getGeoRadiusM(), l.getLatitude(), l.getLongitude(), l.isWfhAllowed());
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> assetStores() {
		return repo.findByOrgIdAndAssetStoreTrueAndActiveTrue(currentUser.orgId()).stream().map(Location::getId).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<LocalDate> workingDays(UUID employeeId, LocalDate from, LocalDate to) {
		UUID location = locationOf(employeeId);
		WorkSchedule schedule = location == null ? orgSchedule() : scheduleFor(location);
		UUID calendar = location == null ? calendars.nationalOnly().orElse(null) : holidayCalendarFor(location);
		Set<LocalDate> daysOff = new HashSet<>();
		if (calendar != null) {
			for (int year = from.getYear(); year <= to.getYear(); year++) {
				daysOff.addAll(calendars.daysOff(calendar, year));
			}
		}
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			if (schedule.isWorkingDay(d) && !daysOff.contains(d)) {
				days.add(d);
			}
		}
		return days;
	}

	private Optional<Location> load(UUID id) {
		if (id == null) {
			return Optional.empty();
		}
		return cache.get(id, k -> repo.findByIdAndOrgId(k, currentUser.orgId()));
	}

	private UUID locationOf(UUID employeeId) {
		return jdbc.sql("select location_id from employee where id = :id").param("id", employeeId)
				.query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
	}

	/** An employee with no location works the organisation's default week and time zone. */
	private WorkSchedule orgSchedule() {
		String tz = jdbc.sql("select timezone from org where id = :id").param("id", currentUser.orgId())
				.query(String.class).optional().orElse("Asia/Kolkata");
		return new WorkSchedule(WeekPattern.MON_FRI, LocalTime.of(9, 30), LocalTime.of(18, 30), 15, zone(tz));
	}

	private static ZoneId zone(String id) {
		return id == null ? ZoneId.of("Asia/Kolkata") : ZoneId.of(id);
	}

	private static LocationView view(Location l) {
		return new LocationView(l.getId(), l.getName(), l.getCode(), l.getType() == null ? LocationType.BRANCH_OFFICE
				: l.getType(), l.getAddressLine(), l.getCity(), l.getState(), l.getPostalCode(), l.getCountry(),
				zone(l.getTimezone()), l.isActive(), l.getHeadEmployeeId(), l.getSeatCapacity(), l.isAssetStore(),
				l.getHolidayCalendarId());
	}
}
