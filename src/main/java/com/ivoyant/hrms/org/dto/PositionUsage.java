package com.ivoyant.hrms.org.dto;

/**
 * What still points at a position. {@code employees} are the people in it now, {@code exitedEmployees} are former
 * employees whose history points here. {@code directReports} counts the positions that report to it.
 */
public record PositionUsage(long employees, long exitedEmployees, long titles, long directReports, long openJobs,
		long pendingOffers, long onboardingDrafts, long approvalRules, long customFieldValues, String message) {

	/** Anything that stops the position from being deleted. */
	public boolean blocksDelete() {
		return employees > 0 || exitedEmployees > 0 || titles > 0 || directReports > 0 || openJobs > 0
				|| pendingOffers > 0 || onboardingDrafts > 0 || approvalRules > 0 || customFieldValues > 0;
	}
}
