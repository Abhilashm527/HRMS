package com.ivoyant.hrms.calendar.service;

import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * The calendars ("layers") an event sits on. What a person may do on each comes from their roles: everyone sees the company, team,
 * department and office calendars and adds to their own, team and department ones; managers also add company and office events (HR
 * approves those); HR and super admins (or anyone with calendar Admin) see and manage every layer; Finance owns payroll dates and HR
 * operations the recruitment calendar.
 */
enum CalendarLayer {
	COMPANY("Company", "Holidays, all-hands and company deadlines", "#1F4FE0"),
	MY("My calendar", "Personal events and reminders · only the owner sees them", "#0F766E"),
	TEAM("Team", "Team leave, 1:1s and team events", "#7C3AED"),
	DEPT("Department", "Department events, birthdays and anniversaries", "#B45309"),
	LOC("Office", "Office events for one location", "#0369A1"),
	HR("HR & admin", "Calibration, exits and other confidential items", "#BE123C"),
	REC("Recruitment", "Interviews and joining dates", "#4D7C0F"),
	FIN("Payroll & finance", "Payroll cut-offs and finance deadlines", "#475569");

	final String label;
	final String description;
	final String color;

	CalendarLayer(String label, String description, String color) {
		this.label = label;
		this.description = description;
		this.color = color;
	}

	static CalendarLayer of(Object value) {
		try {
			return valueOf(String.valueOf(value).trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	enum See {
		FULL, BUSY, NONE
	}

	enum Edit {
		ANY, OWN, NONE
	}

	record Perm(See see, boolean add, Edit edit, boolean needsApproval) {
	}

	/** Who is looking: their place in the org, roles, whether they administer the calendar, and the time zone they read times in. */
	record Viewer(UUID orgId, UUID id, String name, UUID managerId, UUID departmentId, UUID locationId, Set<String> roles, boolean admin,
			boolean manager, ZoneId zone) {

		Perm perm(CalendarLayer l) {
			if (admin) {
				return new Perm(See.FULL, true, Edit.ANY, false);
			}
			boolean fin = roles.contains("finance");
			boolean ops = roles.contains("hr_ops");
			return switch (l) {
				case COMPANY -> manager ? new Perm(See.FULL, true, Edit.OWN, true) : new Perm(See.FULL, false, Edit.NONE, false);
				case MY, DEPT -> new Perm(See.FULL, true, Edit.OWN, false);
				case TEAM -> new Perm(See.FULL, true, manager ? Edit.ANY : Edit.OWN, false);
				case LOC -> manager ? new Perm(See.FULL, true, Edit.OWN, true) : new Perm(See.FULL, false, Edit.NONE, false);
				case HR -> new Perm(manager || ops ? See.BUSY : See.NONE, false, Edit.NONE, false);
				case REC -> ops ? new Perm(See.FULL, true, Edit.ANY, false) : new Perm(manager ? See.BUSY : See.NONE, false, Edit.NONE, false);
				case FIN -> fin ? new Perm(See.FULL, true, Edit.ANY, false) : new Perm(See.NONE, false, Edit.NONE, false);
			};
		}

		/** Holds the role, where "hr_admin" is also met by a super admin. */
		boolean holds(String role) {
			return roles.contains(role) || ("hr_admin".equals(role) && roles.contains("super_admin"));
		}
	}
}
