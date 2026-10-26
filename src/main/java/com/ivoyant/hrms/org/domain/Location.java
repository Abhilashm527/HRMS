package com.ivoyant.hrms.org.domain;

import com.ivoyant.hrms.common.domain.TenantScoped;
import com.ivoyant.hrms.org.LocationType;
import com.ivoyant.hrms.org.WeekPattern;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A place employees are based; it drives pay rules, holidays, work week and attendance for them. */
@Entity
@Table(name = "location")
@Getter
@Setter
public class Location extends TenantScoped {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "code", nullable = false)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false)
	private LocationType type = LocationType.BRANCH_OFFICE;

	@Column(name = "head_employee_id")
	private UUID headEmployeeId;

	@Column(name = "address_line")
	private String addressLine;

	@Column(name = "city")
	private String city;

	@Column(name = "state")
	private String state;

	@Column(name = "postal_code")
	private String postalCode;

	@Column(name = "country")
	private String country;

	@Column(name = "timezone")
	private String timezone;

	@Column(name = "phone")
	private String phone;

	@Column(name = "email")
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "week_pattern", nullable = false)
	private WeekPattern weekPattern = WeekPattern.MON_FRI;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime = LocalTime.of(9, 30);

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime = LocalTime.of(18, 30);

	@Column(name = "grace_minutes", nullable = false)
	private int graceMinutes = 15;

	@Column(name = "holiday_calendar_id")
	private UUID holidayCalendarId;

	/** {@link com.ivoyant.hrms.org.AttendanceMethod} names. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "attendance_methods", nullable = false, columnDefinition = "text[]")
	private List<String> attendanceMethods = new ArrayList<>(List.of("WEB"));

	@Column(name = "geo_radius_m")
	private Integer geoRadiusM;

	@Column(name = "latitude", precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(name = "longitude", precision = 9, scale = 6)
	private BigDecimal longitude;

	@Column(name = "wfh_allowed", nullable = false)
	private boolean wfhAllowed = true;

	@Column(name = "lwf_applicable", nullable = false)
	private boolean lwfApplicable;

	@Column(name = "esi_notified", nullable = false)
	private boolean esiNotified = true;

	@Column(name = "shops_estab_reg_no")
	private String shopsEstabRegNo;

	@Column(name = "gstin")
	private String gstin;

	@Column(name = "seat_capacity")
	private Integer seatCapacity;

	@Column(name = "asset_store", nullable = false)
	private boolean assetStore;

	@Column(name = "active", nullable = false)
	private boolean active = true;
}
