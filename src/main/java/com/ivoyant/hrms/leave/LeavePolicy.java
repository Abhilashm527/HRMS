package com.ivoyant.hrms.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the leave engine (apply, approve, ledger, attendance) reads from Settings → Leave &amp; attendance. It never reads the configuration
 * tables itself. Types are cached and re-read when the policy changes.
 */
public interface LeavePolicy {

	/** Active leave types, in the order the screen shows them. */
	List<LeaveTypeView> activeTypes();

	Optional<LeaveTypeView> type(String code);

	/** Gender and length of service on the given day. */
	boolean eligible(UUID employeeId, LeaveTypeView type, LocalDate on);

	/**
	 * Days the leave counts for: working days (weekly off and day-off holidays of the employee's location left out) or every calendar day,
	 * and half a day when one day is applied for as a half day.
	 */
	BigDecimal countDays(UUID employeeId, LeaveTypeView type, LocalDate from, LocalDate to, boolean half);

	/** Everything wrong with the application, with the screen's messages; empty means it can be submitted. */
	List<ApplyError> validate(UUID employeeId, LeaveTypeView type, ApplyForm form);

	/** The approval chain worked out for this person and number of days. Empty means nobody needs to approve. */
	List<ResolvedStep> chainFor(UUID employeeId, BigDecimal days);
}
