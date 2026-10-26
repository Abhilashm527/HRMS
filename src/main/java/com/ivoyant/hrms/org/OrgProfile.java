package com.ivoyant.hrms.org;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** The organization's profile; cached for a minute and evicted when it changes. */
public interface OrgProfile {

	OrgView current();

	ZoneId zone();

	/** Start of the financial year that contains the date. */
	YearMonth fyStart(LocalDate anyDate);

	boolean isArchived();
}
