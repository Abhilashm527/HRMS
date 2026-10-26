package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ivoyant.hrms.org.LocationType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A location with everything the settings drawer and cards show; {@code stats} only with {@code include=stats}. */
public record LocationResponse(UUID id, String name, String code, LocationType type, String typeLabel,
		Address address, String timezone, String phone, String email, Head head, Schedule schedule,
		HolidayCalendar holidayCalendar, Attendance attendance, Statutory statutory, Integer seatCapacity,
		boolean assetStore, boolean active, @JsonInclude(JsonInclude.Include.NON_NULL) Stats stats, Long version,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

	public record Address(String line, String city, String state, String postalCode, String country) {
	}

	public record Head(UUID employeeId, String name, String initials) {
	}

	public record Schedule(String weekPattern, String weekLabel, String start, String end, int graceMinutes) {
	}

	public record HolidayCalendar(UUID id, String name, String label) {
	}

	public record Attendance(List<String> methods, Integer geoRadiusM, BigDecimal latitude, BigDecimal longitude,
			boolean wfhAllowed) {
	}

	public record Statutory(boolean lwf, boolean esiNotified, String shopsEstabRegNo, String gstin, String ptLabel,
			boolean ptApplies) {
	}

	/** {@code utilisation} is headcount divided by seat capacity (null without capacity). */
	public record Stats(long headcount, Double utilisation, Integer free) {
	}

	public LocationResponse withWarnings(List<String> warnings) {
		return new LocationResponse(id, name, code, type, typeLabel, address, timezone, phone, email, head, schedule,
				holidayCalendar, attendance, statutory, seatCapacity, assetStore, active, stats, version, warnings);
	}
}
